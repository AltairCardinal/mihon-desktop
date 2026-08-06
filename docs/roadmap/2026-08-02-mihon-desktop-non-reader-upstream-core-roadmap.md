# Mihon Desktop 非 Reader 原版核心复用与功能补齐 Roadmap

- 制定日期：2026-08-02
- 最近细化：2026-08-04（依据 `NR0-01` 自动语义映射失控审查）
- 状态：`IN_PROGRESS`（父路线当前唯一 `active-child-plan`）
- 上级路线：[`2026-06-30-mihon-desktop-refactor-roadmap.md`](./2026-06-30-mihon-desktop-refactor-roadmap.md) 的非 Reader Phase R
- Reader 专项：[`2026-08-02-reader-core-migration-and-presentation-roadmap.md`](./2026-08-02-reader-core-migration-and-presentation-roadmap.md)
- 固定原版权威：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`
- 本次上游跟踪点：`upstream/main@d7f3ceef5c75294306d0d9495e9ebc5ffca96302`（2026-08-02）
- 当前 Fork 已提交基线：`95b82fc1039f772d4f8688855f2b06e16f983eb5`
- 历史对齐总结：[`2026-08-02-mihon-desktop-upstream-parity-program-summary.md`](./2026-08-02-mihon-desktop-upstream-parity-program-summary.md)
- 机器状态权威：[`parity-manifest.json`](../../app-desktop/src/test/resources/parity/parity-manifest.json)
- 当前进度：从第 7 节第一个未勾选顶层任务，以及该任务中第一项非 `PASS` checkpoint 推导；不另设 `active-task`

当前 Fork 基线只指已提交树；制定本文时存在的未提交工作树改动不作为“已实现”或“已验证”证据，也不由本文修改。

## 1. 结论、目标与前次对齐为何收效有限

前一次对齐并非无效。它已经带来了可继续复用的基础：大量 `domain`、`data`、SQLDelight、偏好、错误模型、分类、书库过滤、追踪仓库、备份格式和若干共享 reducer/use case 已被 Android 与 Desktop 使用。问题在于，当时的“完成单位”通常是一个能力切片或一个共享契约，而不是一条完整产品调用链，因此“某项 capability 已验证”并不总是等于以下三件事同时成立：

1. 原版页面中的每个用户动作都已出现在 Desktop，且入口、反馈、确认、空状态和失败状态完整；
2. 当前 Android 与 Desktop 的生产入口实际执行同一份从原版提取的业务核心；
3. Desktop 的临时重写和 Android 的旧决策实现已经删除，只剩必要的平台 adapter。

这解释了为什么基础架构进步明显，但用户仍会在漫画详情、更新、历史、统计、迁移等页面看到功能缺口，也解释了为什么 `MangaDetailScreenModel`、`SaveSourceMangaForDetails`、`LibraryUpdateChecker` 等 Desktop 实现仍在重新解释原版已有规则。历史计划还固定在较早原版点；较新的上游已经增加 `UpdateMangaFromRemote` 等统一链路，进一步暴露了当前 Fork 的重复实现。

本路线图把完成定义改为：

- 以原版的动作级行为和较新上游修复为权威，而不是只比较页面名、类型名或共享 DTO；
- 对平台无关逻辑采用“从原版提取并保留代码谱系”，当前 Android 先成为共享核心消费者，Desktop 再接入；
- 对 Android/Desktop 确实不同的 API，只共享决策、状态、错误和数据契约，平台层保留薄 adapter；
- 每个用户可见动作必须有 Desktop 入口、成功/失败反馈，危险动作必须有确认；
- 每个批次必须删除或明确到期处理被替代的第二套实现，不能以 facade 包装双轨后宣称复用完成；
- Reader 专项完全由现有 Reader roadmap 负责，本文不重复排期。

最终目标不是让 Desktop UI 变成 Android UI，而是让两端在非 Reader 领域共享同一个业务事实来源；Desktop 继续保留宽屏布局、键鼠操作、文件系统/CBZ、Test Mode、JAR-first 扩展、FlareSolverr 等产品能力。

## 2. 权威、范围与 Reader 排除边界

### 2.1 三层权威

| 层级 | 用途 | 规则 |
| --- | --- | --- |
| 固定原版 `main@6fbf6df…` | 证明原版已有的行为、默认值、状态转换和实现边界 | 每个任务记录路径、symbol、fixture；当前 `app/` 不能反向自证原版行为 |
| 上游跟踪点 `upstream/main@d7f3cee…` | 吸收固定点之后的修复与重构，例如 `UpdateMangaFromRemote` | 每阶段开始前只 fetch/比较一次；语义变化先更新契约，再迁移实现 |
| 当前 Fork | 保护 Desktop 增强及已经共享的基础设施 | 已存在代码只是候选资产；必须由 production wiring 测试证明实际消费 |

历史 parity summary、功能比较文档和现有 `VERIFIED` 条目继续作为证据索引，但不能覆盖本计划更细的动作级结论。`NR0-01` 只扩展现有 `parity-manifest.json` 的动作面、复用门禁和证据，不创建第二份机器状态源。

### 2.2 迁移分类

| 标签 | 含义 | 处置 |
| --- | --- | --- |
| `SHARE-DIRECT` | `domain`/`data` 已有原版或当前共享实现 | 两端直接消费，补 production wiring 证据，删除调用方重复规则 |
| `SHARE-EXTRACT` | 原版逻辑仍位于 Android `app/`，但不依赖平台 UI/API | 保留算法和测试谱系提取到 KMP/shared；当前 Android 与 Desktop 同时消费 |
| `PLATFORM-PORT` | 核心可共享，I/O、系统服务或 UI 生命周期不同 | 共享接口、状态和错误；Android/Desktop 各实现薄 adapter |
| `DESKTOP-PRODUCT` | Desktop 永久增强 | 叠加在共享基线之后，单独回归保护，不冒充原版行为 |
| `READER-OWNED` | 已由 Reader roadmap 覆盖 | 本文只定义输入/输出边界，不实现、不验收其内部行为 |
| `DROP-LEGACY` | 早期移植的简化重写或临时桥 | 消费者切换后删除；不能长期留作 fallback |
| `PLATFORM-EXEMPT` | Desktop 没有可靠等价系统能力 | UI 明示不支持或提供诚实降级，并记录复查条件 |

### 2.3 本文包含与排除

本文包含：远端作品/章节同步、漫画详情全部动作、书库与分类、书库更新、下载管理、Updates、History、Stats、迁移、追踪、浏览/全局搜索、扩展、备份恢复、设置以及非 Reader 平台工作流。

以下内容明确由 Reader roadmap 独占，本文不得重复创建任务：

- ReaderSession、ChapterSession、PageSession 和稳定页身份；
- 页列表加载、单页 materialize、优先级调度、取消、重试和 encoded cache；
- previous/current/next 章节窗口、跨章加载和下一章预取；
- viewport 驱动的阅读进度、末页完成和自动标记已读；
- Single、Webtoon、Dual presentation 及其 Compose/Skia adapter；
- Reader 内部的下载/本地/归档取页、解码和生命周期。

允许的边界只有三类：

1. 详情、Updates、History 选择章节后生成稳定的 `ReaderOpenRequest`；请求交给 Reader 入口后，本文任务结束。
2. 详情页可以保存每本漫画的阅读模式/方向偏好；这些偏好如何驱动 Reader 由 Reader roadmap 验收。
3. 非 Reader 页面手动标记章节已读时可以触发追踪更新；Reader 根据阅读进度触发的追踪事件仍由 Reader 计划负责，不能在两处重复实现。

## 3. 当前差异与可直接复用的核心

| 领域 | 当前可复用资产 | 当前主要差异/重复 | 目标任务 |
| --- | --- | --- | --- |
| 数据、模型、仓库 | `domain/`、`data/`、SQLDelight、现有 manga/chapter/category/history/track interactors | 已共享基础较强，但页面仍可能绕过或在调用方重新解释规则 | 全部任务的默认基础；`NR0-02` 守卫 |
| 远端作品更新 | 较新上游 `UpdateMangaFromRemote`；现有 `SyncChaptersWithSource` | 当前 Fork 尚无统一 remote update；Desktop `SaveSourceMangaForDetails` 与详情/更新链重复 | `RS-01`–`RS-03` |
| 章节同步 | 原版/上游 `SyncChaptersWithSource` 的去重、清洗、识别、removed 处理、下载重命名、read/bookmark/dateFetch 保留、扫描组排除和 fetch interval | Desktop 主要按 URL 增改及识别章节号，语义明显更窄 | `RS-01` |
| 漫画详情状态 | Android `MangaScreenModel` 的处理后章节、下载态、tracker、对话框和错误语义；已有共享 `BatchUpdateChapters`、`UpdateLibraryMembership` | Desktop model 仍是独立状态容器；多个动作和默认策略缺失 | `MD-01`–`MD-07` |
| 详情过滤/排序 | `SetMangaChapterFlags`、`SetMangaDefaultChapterFlags`、原版 tri-state/filter/sort/display 规则 | Desktop 过滤多为页面内布尔状态；缺少负向过滤、默认值、应用到现有作品等完整语义 | `MD-02` |
| 收藏/分类 | `GetDuplicateLibraryManga`、`UpdateLibraryMembership`、分类 interactors | Desktop 已有原子收藏/分类，但缺 duplicate/default category/移除时下载处理等完整策略 | `MD-03` |
| 继续阅读/批量下载 | 原版处理后章节、排序、`skipFiltered`、已下载/队列过滤策略 | Desktop 仍可能从原始 source order 选目标 | `MD-04`、`DL-01` |
| 章节批量动作 | `BatchUpdateChapters`、章节 repository/use cases | Desktop 有 checkbox/select all，但缺 range/invert/等价键鼠动作和部分追踪/失败反馈 | `MD-05`、`TR-02` |
| 封面与元数据 | `UpdateCustomCover`、原版 cover/description/notes/source-search 行为 | Desktop 主要覆盖编辑/删除封面与简化文本；缺查看、保存/分享和完整元数据交互 | `MD-06` |
| 书库/分类 | `EvaluateLibrary`、分类 interactors、共享 membership/batch use cases | 基础复用较好；仍需以动作级矩阵复核最新上游菜单、空/错状态和批处理 | `LB-01` |
| 书库更新 | 原版 `LibraryUpdateJob`、`UpdateMangaFromRemote`、章节同步与限制规则；共享后台生命周期 | Desktop `LibraryUpdateChecker` 是简化同步器，调度共享不等于更新 executor 已共享 | `LU-01` |
| 下载 | 现有 Desktop 下载 manager/queue、原版下载选择和动作规则 | 文件执行必须平台化，但 eligible/select/retry/delete/batch 规则仍可能分叉 | `DL-01`、`DL-02` |
| Updates | `GetUpdates`、章节/下载 use cases、原版 selection actions | Desktop 有基础过滤、批量已读、下载和阅读入口，缺完整筛选/选择/书签/删除等动作 | `UP-01` |
| History | history interactors、`GetDuplicateLibraryManga`、原版 History model | Desktop 已有搜索、删除、清空，仍需补原版完整动作与共享决策 | `HI-01` |
| Stats | `GetTotalReadDuration`、`GetTracks`、原版 Stats 聚合 | Desktop 当前主要是漫画/阅读/来源/章节基础统计，缺下载、时长、tracker、评分等维度 | `ST-01` |
| Migration | 当前 Android `MigrateMangaUseCase` 与较新上游版本；分类、章节、track、download、cover use cases | Desktop 选项主要为 chapter/category/notes，核心与 Android 分离 | `MG-01`、`MG-02` |
| Tracking | 共享 track repository/interactors；Android `TrackChapter`、刷新/延迟队列 | 服务 provider 已有较多复用，但手动已读、失败重试和详情联动仍未统一 | `TR-01`、`TR-02` |
| Browse/Search | 已有 source reducer/query/error contracts、source API、Desktop 搜索历史增强 | 页面状态有共享切片，但搜索/分页/materialize/收藏入口仍需完整生产链复核 | `BR-01` |
| Extensions | 共享 catalog/model/API 资产；Desktop JAR loader 与诊断 | 安装包、ClassLoader、WebView/浏览器必须平台化；catalog/update/install 决策应继续收敛 | `EX-01` |
| Backup/Restore | protobuf 模型、字段往返和部分 Desktop 测试 | Android/Desktop 仍有独立 creator/restorer orchestration；格式一致不等于恢复决策一致 | `BK-01` |
| Settings/平台流程 | typed preferences、共享错误模型、平台 adapters | 需复核全部原版设置入口、默认值、迁移、依赖显隐和 Desktop 诚实降级 | `SE-01`、`PA-01` |
| Reader | Reader roadmap | 本文不处理 | `READER-OWNED` |

## 4. 目标架构与不可违反的规则

```text
Android ScreenModel / Desktop ScreenModel
                  │
                  ▼
     shared use case / reducer / policy
       ├── 原版动作语义与状态转换
       ├── shared repository contracts
       ├── typed result / partial failure
       └── platform-neutral effect request
                  │
          ┌───────┴────────┐
          ▼                ▼
   Android adapter    Desktop adapter
   WorkManager/URI    scheduler/file picker
   WebView/Share      browser/clipboard
   APK installer      JAR loader
   notifications      in-app/native fallback
```

不可违反的规则：

1. 新共享核心必须能指出其来自固定原版或较新上游的哪些 symbol/测试；不得只看行为说明后重新发明另一套实现。
2. `SHARE-EXTRACT` 批次必须让当前 Android 生产调用方成为消费者；只让 Desktop 使用新 core 不算从原版提取成功。
3. UI 只负责输入、布局、导航和呈现。筛选、目标选择、批量结果、更新资格、迁移决策和恢复合并不能留在 Composable。
4. shared 层不得引用 Android `Context`、WorkManager、Uri、WebView，也不得引用 Desktop Compose、AWT/Swing、Voyager、Skia、文件选择器或 ClassLoader。
5. platform adapter 不得自行决定章节保留、更新限制、下载资格、迁移字段、追踪进度或备份冲突策略。
6. Desktop 产品增强通过 decorator/policy/额外入口叠加；与共享核心冲突时扩展核心，不复制核心。
7. 迁移桥只能跨越连续两个任务，必须登记删除任务与截止门禁；不得长期保留双 executor fallback。
8. 源码扫描和依赖图只能证明边界，不能代替真实 production 行为、DI、导航、HTTP、数据库和 mounted UI 测试。

## 5. 状态、checkoff 与完整状态卡

### 5.1 状态枚举

| 状态 | 含义 |
| --- | --- |
| `TODO` | 尚未开始或依赖未满足 |
| `NEXT` | 依赖明确，可作为下一功能批次 |
| `DOING` | 正在执行红绿重构与生产迁移 |
| `BLOCKED` | 有已记录的真实外部/架构阻塞 |
| `REVIEW` | 实现完成，等待独立审查、验证或提交 |
| `DONE` | 状态卡所有适用字段关闭且已有 commit |
| `DEFERRED` | 经明确决策延期，并记录复查条件 |

- `[ ]` 在 `TODO/NEXT/DOING/BLOCKED/REVIEW` 都保持未勾选。
- `[x]` 只有在实现、独立审查、相关验证和功能批次提交均完成后勾选。
- 每项状态卡字段必须为 `[x]`，或记录 `N/A：<理由>`；留空、口头说明或仅有源码字符串测试不能关闭任务。
- 一个功能批次原则上一个提交，包含 RED、production、重构、测试和必要 checkoff；审查修复最多再追加一个提交。
- 任务超过 8 个文件或约 400 行时记录内聚性和风险，不为降低数字拆开不可运行的 production 链。
- 现有实现可直接作为 GREEN 候选，但仍必须先建立能因正确缺口失败的测试；不得事后补一份无法约束 wiring 的测试。
- 本文所有任务初始未勾选，不表示既有共享成果无效；只表示它们尚未按“动作完整、双端真实消费、Legacy 处置、审查、验证、提交”这一组更强关闭条件重新证明。

### 5.2 每个任务保留的状态字段

每个详细任务都预留同一状态卡：

> 状态卡：`状态` · 权威/范围 · RED/基线 · Shared · Android · Desktop/UI · Legacy · Review · Verify · Evidence · Commit
>
> 记录：阻塞原因 · 审查结论 · 验证命令/结果 · 产物 · Commit hash

字段含义：

- **权威/范围**：固定原版 symbol、tracked-upstream 差异、Desktop 增强和 Reader 排除边界已冻结。
- **RED/基线**：行为任务确认正确 RED；纯审计任务确认缺口/证据基线测试可执行。
- **Shared**：共享实现完成，或明确记录为何只能是 adapter/exempt。
- **Android**：当前 Android 真实生产消费者和测试完成，或记录适用的 N/A。
- **Desktop/UI**：Desktop 真实入口、反馈、确认、空/错状态和 wiring 完成，或记录适用的 N/A。
- **Legacy**：被替代实现已删除/不可达，或登记保留理由、责任边界和复查条件。
- **Review**：一轮独立审查完成；修复后最多一轮复审。
- **Verify**：focused、阶段门禁或最终矩阵按任务要求通过。
- **Evidence**：manifest、文档、测试报告或运行产物已记录。
- **Commit**：只在批次提交存在后填写 hash；提交前 checkbox 不得勾选。

### 5.3 强制小步执行、工具边界与停止条件

以下约束适用于本文全部任务，尤其适用于 inventory、架构审计和大规模迁移：

1. **先定义禁止证据，再写正向实现。** 每个任务先写出不能证明完成的证据，以及至少 3 个会破坏目标行为的反例；反例未按正确原因失败前，不得扩展到完整页面、完整 capability 或全部平台。
2. **最小先导切片先于批量扩展。** 审计类任务先处理 20 个高风险样本；业务任务先处理一个动作族和一条双端 production 调用链；平台任务先处理一个 shared effect 和两个 adapter。先导切片未通过内部 stop-gate，不得批量生成或迁移其余内容。
3. **执行 checkpoint 与 Git task 分离。** 一个顶层功能批次仍按治理规则原子提交，但内部必须拆成可在 0.5–3 工程日内完成的 checkpoint。checkpoint 使用 `TODO / DOING / REVIEW / PASS / INVALIDATED`，第一项非 `PASS` 即内部进度；`PASS` 不等于顶层任务完成，也不单独产生状态提交。
4. **过程工具不得升级为隐含产品。** 除任务明确列出的交付物外，不新增生成器、规则引擎、状态系统或长期快照。持久化 helper 只能做机械发现、确定性 ID/hash、Git 对象读取、排序和序列化；一旦开始选择业务锚点、判断 action 语义、决定 capability 归属、生成 GAP/EXEMPT 或维护 synonym/override/exception 表，立即停止并回到计划审查。
5. **测试不得与实现互相证明。** 测试不得调用生成器的语义函数，也不得复制其 token、synonym、候选评分或 override 逻辑。结构测试只能证明 schema、引用、路径和 drift；语义正确性由手写破坏性反例、逐簇源码审查和 production 行为测试证明。
6. **系统性失败不得补丁式扩张。** 同类错误在 2 个样本或 2 个 capability 出现、一个 cluster 需要超过 5 个同类例外、或审查指出方法层缺陷时，当前 checkpoint 标记 `INVALIDATED`，停止扩大数据集；删除错误方法并回到最近 `PASS` checkpoint，禁止继续追加规则或豁免。
7. **颗粒度上限。** inventory 语义审查每个 cluster 最多 8 个 capability 且最多 60 个 action；超过任一上限必须先按页面/动作族再拆。业务迁移 checkpoint 最多覆盖一个动作族、一个 shared core 和 Android/Desktop 各一条生产入口；需要三个以上独立动作族时，先在任务内增加 checkpoint。
8. **每个 checkpoint 有固定六项。** 开始前写明输入、允许动作、禁止动作、交付物、通过门禁和 timebox；任何新增流程不属于这六项时，先暂停并更新路线图，不能边执行边隐性扩展。

这些 checkpoint 只记录在对应详细任务的状态表和最终证据行中，不新增第二份计划、逐阶段 manifest 快照或巨型 diff 包。

## 6. 阶段与任务总览

| 阶段 | 目标 | 退出门禁 |
| --- | --- | --- |
| Phase 0 | 先建立独立负例与人工语义裁决方法，再把权威升级到动作级 | 20 个高风险样本和 8 个 capability cluster 逐段通过；不存在自动语义豁免或未审查跨 capability 关系 |
| Phase 1 | 远端作品与章节同步成为唯一共享核心 | Android 与 Desktop 的详情/materialize 链使用同一 core；简化重写删除 |
| Phase 2 | 补齐漫画详情页全部原版动作并共享决策 | 详情动作矩阵全绿；只保留平台 UI/adapters 与 Desktop 增强 |
| Phase 3 | 收口书库、书库更新和下载 | 更新资格、同步和下载动作规则不再由平台各自解释 |
| Phase 4 | 补齐 Updates、History、Stats | 三页功能、错误/空状态和跨页动作达到动作级 parity |
| Phase 5 | 收口 Migration 与 Tracking | 迁移字段和追踪副作用一致，详情页入口完成最终接线 |
| Phase 6 | 复核 Browse、Extensions、Backup、Settings 与平台流程 | 可共享逻辑共享；真正平台差异有薄 adapter 或明确豁免 |
| Phase 7 | 删除 Legacy、更新机器权威并做最终验收 | 非 Reader 生产调用图单一，Android/Desktop/发布矩阵通过 |

## 7. 顶层任务清单

### Phase 0：权威与架构门禁

- [ ] `NR0-01` 按“负例 → 20 样本 → 8 个 capability cluster → tracked/source 图”建立动作级 inventory
- [ ] `NR0-02` 建立共享核心、双端消费者和 Legacy 删除的可执行架构门禁

### Phase 1：远端作品与章节同步

- [ ] `RS-01` 从原版提取唯一章节同步核心并切换当前 Android
- [ ] `RS-02` 从较新上游提取统一远端作品更新核心并切换 Android 全部调用方
- [ ] `RS-03` 切换 Desktop 详情/浏览/deep-link materialize 链并删除简化重写

### Phase 2：漫画详情全部动作

- [ ] `MD-01` 提取详情观察、刷新和 processed chapter 状态核心
- [ ] `MD-02` 补齐并共享章节过滤、排序、显示、扫描组及详情设置
- [ ] `MD-03` 补齐收藏、重复项、默认分类、移出书库和下载清理策略
- [ ] `MD-04` 统一继续阅读与批量下载目标选择（阅读部分仅生成 Reader 请求）
- [ ] `MD-05` 补齐选择、范围/反选、滑动/键鼠等价动作和批量反馈
- [ ] `MD-06` 补齐封面、描述、元数据、笔记和来源搜索工作流
- [ ] `MD-07` 完成追踪/迁移入口接线、详情全页门禁和第二套详情决策清理

### Phase 3：书库、书库更新与下载

- [ ] `LB-01` 复核书库/分类所有动作并直接复用已有 shared interactors
- [ ] `LU-01` 提取书库更新资格、限制、同步、通知决策并切换双端 executor
- [ ] `DL-01` 提取跨页面下载资格、目标选择和动作结果核心
- [ ] `DL-02` 补齐 Desktop 下载队列动作并统一详情/书库/Updates wiring

### Phase 4：Updates、History、Stats

- [ ] `UP-01` 迁移 Updates 完整查询、筛选、选择和批量动作
- [ ] `HI-01` 迁移 History 完整查询、删除、恢复入口和书库联动
- [ ] `ST-01` 直接复用原版统计聚合并补齐 Desktop 全部统计区块

### Phase 5：Migration 与 Tracking

- [ ] `MG-01` 提取最新上游 Migration 核心和完整迁移字段
- [ ] `MG-02` 切换 Android/Desktop 迁移入口并保留 Desktop 事务/检查点增强
- [ ] `TR-01` 提取追踪更新、刷新、延迟重试和 typed result 核心
- [ ] `TR-02` 统一手动已读与追踪联动并完成详情/Updates 接线

### Phase 6：其余非 Reader 产品面

- [ ] `BR-01` 复核并收口 Sources、Browse、Global Search 的共享查询与 materialize 链
- [ ] `EX-01` 收口扩展 catalog/update/install 决策并隔离 APK/JAR/浏览器 adapters
- [ ] `BK-01` 提取备份创建/恢复计划与冲突合并核心并完成双端接线
- [ ] `SE-01` 建立原版设置动作矩阵并补齐默认值、迁移、显隐和 Desktop UI
- [ ] `PA-01` 复核 deep link、web/share、认证挑战、通知、更新与诊断平台流程

### Phase 7：收口

- [ ] `QG-01` 删除所有非 Reader legacy executor/桥并收紧依赖图守卫
- [ ] `QG-02` 更新机器权威并完成 Android/Desktop 最终发布验收

## 8. 详细任务设计

### `NR0-01` 分段建立非 Reader 动作级 inventory

> 状态卡：`DOING` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `N/A：本任务只建立机器权威，不改变共享生产实现` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `N/A：本任务不替换或删除生产实现` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：当前 checkpoint `NR0-01.F3（TODO；按用户要求暂停，尚未开始）` · 阻塞 `无外部阻塞；旧自动语义映射方法已 INVALIDATED；恢复后从 F3 继续，禁止追加 override/exception` · 审查 `E 主协调者只读方法审查 PASS；F1/F2.1/F2.2 人工逐项裁决及各自 3 个 cluster mutation PASS；既有两轮 CHANGES_REQUIRED 仍仅作为旧方法历史` · 验证 `F2.2 RED 的 5 项失败均来自旧 schema、缺失 Reader/risk/search-boundary 契约；GREEN 为 25 tests / 0 failures，55 actions 的 165 个 authority role 均为 MANUAL，精确 Git line/locator/contextHash 检查通过` · 产物 `A–F2.2 PASS；F3 未读取、未生成、未修改` · Commit `—：NR0-01 仍按 A–H 原子批次执行，不创建 checkpoint 提交`

- 依赖：无；本文内部进度从下表第一项非 `PASS` checkpoint 推导。
- 实际交付物：在现有 `parity-manifest.json` 中形成经过人工逐簇裁决的“surface → action → 固定原版/当前 Android/Desktop 生产证据 → tracked-upstream 差异 → source 关系 → 迁移标签/缺口”矩阵，并用独立负例、结构测试和审查锁定结果。
- 非交付物：自动语义映射器、通用 Kotlin/Compose 行为理解器、关键词 ontology、候选评分器、自动 GAP/EXEMPT 判定器或第二份 inventory 状态系统。
- 原子提交：A–H/F1–F8 是同一功能批次的内部 checkpoint；中途只记录 `PASS/INVALIDATED`，不单独创建状态提交。最终提交仍包含收敛后的 manifest、机械 helper（若保留）、独立契约测试和 roadmap checkoff。

#### 审查结论到路线约束的映射

| 审查发现 | 本次纠正 |
| --- | --- |
| inventory 被隐性扩大成自动语义映射器 | helper 限定为机械发现/序列化；语义字段全部人工裁决，自动语义函数必须删除 |
| 一次生成 415 actions/458 entries 后才审查 | 先 20 个高风险样本，再按 F1–F8 单 cluster 扩展；每 cluster 有独立 stop-gate |
| 纯文案、Cancel、默认返回值和无关 source 也能成为证据 | 先冻结“绝对禁止证据”表；结构全绿不能覆盖语义禁区 |
| 没有破坏性反例，明显串线仍全绿 | 完整 manifest 前先跑 12 类手写负例；`external.share → AndroidCookieJar` 是固定哨兵 |
| 测试复制 generator 的 token/semantic 逻辑 | 测试与 helper 禁止共享或复制语义词表；digest 降级为 drift guard |
| 审查指出系统性问题后仍继续加规则/例外 | 同类问题出现两次即 checkpoint INVALIDATED，回到最近 PASS；禁止追加 override/豁免继续扩张 |

#### 绝对不能作为动作语义证据的内容

| 禁止证据 | 为什么无效 | 最低可接受替代 |
| --- | --- | --- |
| 纯文案、字符串资源、标题、图标或 content description | 只能证明有文本/资源，不能证明动作存在或执行 | 生产 handler/intention 到 use case/effect 的调用位置，以及用户可见结果 |
| 通用 Cancel、Dismiss、Back、Close、`navigator.pop()` | 几乎所有对话框都包含，无法证明目标 capability | 只有目标本身就是取消/关闭流程时才可作为 action；否则只标为容器控制 |
| 接口声明、默认返回值、空实现、placeholder、stub | 不能证明 production override 或调用方实际使用 | 具体实现 + production caller；两者必须能追到同一行为 |
| 测试、preview、sample、fixture、注释或 dead code | 不能证明真实入口 | `app/`、`app-desktop/` 或 shared production 路径及其真实 wiring |
| 仅词语相似、同义词命中、最近 symbol 或候选评分最高 | 词法接近不等于业务关系 | 直接调用、数据流、状态转换或同一用户 intent 的源码上下文 |
| Screen/Tab/Dialog 类声明、构造器、DI 注册 | 只证明容器/依赖存在 | 具体入口事件、业务调用和反馈；容器可单独记为 surface/source container |
| preference key/default 或 repository getter | 只证明存储/读取存在 | 设置 UI/生产消费者 + 行为改变或持久化结果 |
| background job、shortcut 声明但无消费路径 | 只证明触发器被声明 | 触发器到同一 shared use case/effect 的 production 调用链 |
| 与目标无关的平台 adapter，例如用 CookieJar 证明 Share | 平台同属一组不代表行为相关 | 目标 effect 的实际 adapter 和成功/失败结果 |
| test-only source、兼容 shim 或 Reader 内部实现 | 不能证明非 Reader 产品动作 | 对应非 Reader 入口；Reader 内部一律交给 Reader roadmap |
| 同一个 source entry 自动分配给多个 capability | 容易制造跨 capability 假关系 | 默认归属一个 capability；确需共享时逐项写明实际多消费者调用链和人工理由 |

结构测试可以验证路径、行号、context hash 和双向引用一致，但这些条件即使全绿也不能把上述禁止证据变成有效语义证据。

#### 机械 helper 的允许范围

`scripts/nr0_01_action_inventory_v2.py` 可以缩减后保留，也可以删除并改用一次性只读命令；无论形式如何，都必须满足：

- 允许发现/输出：`revision`、`platform`、`path`、`line`、原始 `locator`、显示用 declaration、entry kind、`contextHash`、确定性 `sourceEntryId`、Git 中存在/删除/移动的机械事实、稳定排序和 JSON 序列化。
- 可以原样序列化已经人工填写的 action/capability/source 关系，但不得创建、选择、改写或补全这些关系。
- 禁止生成：action/surface/capability 归属、业务锚点、`migrationTag`、`implementationStatus`、GAP/EXEMPT、feedback、confirmation、follow-up task、`sourceEntryIds` 关系或 tracked 语义结论。
- 禁止继续维护 `ANCHOR_OVERRIDES`、`semantic_groups`、synonym/token matching、候选评分、自动 exception 或任何等价机制；发现机械路径后应把候选交给人工 checkpoint，而不是“提高匹配率”。
- `NonReaderActionInventoryContractTest` 必须删除/替换与生成器等价的 `semanticGroups`、`tokenMatches`、synonym 和 candidate-shape 判断；digest 只能作为已审查基线的 drift guard，不能证明初始语义正确。

#### 独立破坏性负例门禁

在接触完整 manifest 前，测试夹具必须手写至少以下 12 类错误映射，并证明每类因独立、清晰的原因失败；同时保留至少 2 个合法正例，防止测试变成“一律拒绝”：

1. `external.share` 绑定到 `AndroidCookieJar`。
2. 删除/清空能力绑定到 Cancel/Dismiss 文案或按钮。
3. 下载暂停绑定到接口默认 `false`、空实现或仅声明。
4. 设置动作只绑定 preference key/default，没有 UI 或生产消费者。
5. 详情刷新绑定到 test/preview 中的同名 `getMangaDetails`。
6. History 清空绑定到通用 `navigator.pop()`。
7. Tracking 更新只绑定 repository getter，没有远端更新/结果处理。
8. Share/Save 绑定到无关 browser/cookie adapter。
9. 一个 source entry 无理由绑定到两个无关 capability。
10. Desktop product 被伪造为 fixed-original `PRESENT`，或平台缺口被自动标为 `EXEMPT`。
11. Reader 页加载/进度动作被收入非 Reader inventory。
12. tracked-upstream 路径仍存在，但目标行为已删除、移动到另一 symbol 或语义改变，却被文件级比较判为 `UNCHANGED`。

负例测试不得导入或复制 helper 的语义词表；若测试只能通过新增某个 action ID 特判，应先判断 schema/方法是否错误，不能直接扩大 allowlist。

#### 内部 checkpoint 状态与 timebox

| Checkpoint | 状态 | 固定目标与交付物 | 通过门禁 | Timebox |
| --- | --- | --- | --- | --- |
| `NR0-01.A` 止损与资产分级 | `PASS` | 冻结全量扩展；把现有字段分为机械候选、人工待审、应删除的自动语义三类 | 不再新增 override/exception；任务记录明确可保留/作废范围 | 0.5–1 日 |
| `NR0-01.B` 证据模型与禁止项 | `PASS` | 固定 action/surface/container/source、PRESENT/GAP/EXEMPT、跨 capability 关系的人工裁决规则 | 上述禁止证据全部进入规范；无“关键词命中即有效”路径 | 1 日 |
| `NR0-01.C` 独立负例夹具 | `PASS` | 建立 12 类破坏性负例和至少 2 个正例，不读取完整 manifest | 12 类均按各自原因 RED，正例 GREEN；测试不复用 helper 语义 | 1–2 日 |
| `NR0-01.D` 机械 helper 收缩 | `PASS` | 删除自动 anchor/semantic/relationship/status 逻辑，仅保留机械发现和序列化 | helper 输出零语义决策；测试中无复制的 semantic matcher | 1–2 日 |
| `NR0-01.E` 20 个高风险先导样本 | `PASS` | 人工核对 20 个非随机样本的四方上下文和 source 关系 | 20/20 逐项通过内部方法审查；全部负例仍有效 | 1–2 日 |
| `NR0-01.F1` 平台基础 | `PASS` | capability `3,4,7,8,9,10,11,12`；`9` 只审非 Reader 设置/入口边界 | 本 cluster 人工裁决、3 个 cluster mutation、零自动豁免 | 1–2 日 |
| `NR0-01.F2.1` Category/Library/批量动作 | `PASS` | capability `16,17,19`；旧 provisional 共 38 actions | 本段独立人工裁决与 3 个 mutation；Library action 不用容器/通用按钮代证 | 1–2 日 |
| `NR0-01.F2.2` 详情 membership/chapters/cover | `PASS` | capability `22,24,26`；旧 provisional 共 57 actions | 本段独立人工裁决与 3 个 mutation；Reader 只留打开请求边界 | 1–2 日 |
| `NR0-01.F3` Browse/Extension A | `TODO` | capability `28,29,30,32,33,34` | 同上；多源和扩展 catalog 关系逐项追调用链 | 1–2 日 |
| `NR0-01.F4` Browse/Extension B | `TODO` | capability `35,36,37,38,39,40` | 同上；认证/挑战/平台 loader 分类明确 | 1–2 日 |
| `NR0-01.F5` Download/Updates/History | `TODO` | capability `56,57,59,61,62,64,66` | 同上；background 与用户动作分开，不共享伪锚点 | 1–2 日 |
| `NR0-01.F6` Migration/Tracking/Backup | `TODO` | capability `67,68,69,70,71,72,73,74` | 同上；数据动作、平台 I/O 和反馈分别取证 | 1–2 日 |
| `NR0-01.F7` 平台能力 | `TODO` | capability `81,82,83,84,85,86,87,88` | 同上；85 等豁免必须人工证明平台边界 | 1–2 日 |
| `NR0-01.F8` Settings/Maintenance | `TODO` | capability `90,91,92,93,94,95,96` | 同上；preference 声明不能代替入口与消费行为 | 1–2 日 |
| `NR0-01.G` tracked/source 图 | `TODO` | 在已通过 F1–F8 的人工 action 上补 tracked diff 和双向 source 关系 | 零文件级默认结论、零未审查跨 capability 绑定 | 2–3 日 |
| `NR0-01.H` 聚合、独立审查与提交 | `TODO` | 合并 cluster 结果，运行 focused/格式，完成一次独立审查和必要复审 | 全部 checkpoint PASS；一个原子提交关闭 NR0-01 | 2–3 日 |

#### `NR0-01.A` 止损与资产分级记录

| 分级 | 现有资产 | 处理决定 |
| --- | --- | --- |
| 机械候选，可保留 | manifest 中的固定 revision、platform、path、line、原始 locator、显示 declaration、entry kind、contextHash，以及仅由这些机械字段确定的 source entry ID | 只作为后续人工裁决的候选输入；必须重新核对 Git blob 和上下文，不能单独证明 action、归属或实现状态 |
| 人工待审，不继承结论 | 现有 surface/action 工作队列、action 到 capability/surface 的归属、四方 evidence 选择、migration tag、implementation status、feedback/confirmation/follow-up、GAP/EXEMPT、tracked disposition、sourceEntryIds 与双向 source graph | F1–F8/G 按 cluster 逐项人工重建；旧值不得作为默认答案，415/458 也不是必须保留的数量下限 |
| 自动语义，应删除 | helper 中的 `ANCHOR_OVERRIDES`、`semantic_groups`、synonym/token matching、候选评分、自动 action/surface/capability 归属、自动 GAP/EXEMPT/desktop-product、自动 source/tracked 关系与 exception；契约测试中复制的同义词、token、candidate-shape 和语义期望表 | 在 D checkpoint 删除而不是修补；相关旧 GREEN 仅记过程历史，不作为完成证据 |
| 路线衔接，可保留 | 父 roadmap 的 active-child 切换与已完成 Reader child plan 状态 | 作为路线状态衔接保留，但不作为 NR0-01 语义证据 |
| 当前任务范围外，不触碰 | 在途 Global Search 历史功能、AppVersion/preferences 相关修改、其测试以及 `icon.png`/`icon.psd` | 视为用户或其他批次改动；不读取为 inventory 结论、不改写、不纳入 NR0-01 提交 |

止损自检：从旧方法被判定 `INVALIDATED` 起没有新增 override、exception 或全量生成；在 B/C/D 完成前禁止再次运行会重写完整 manifest 的 helper。后续可以删除伪 action、改变归属或减少计数，不以保住旧产物规模为目标。

#### `NR0-01.B` 人工证据裁决契约

| 实体 | 定义 | 可以证明什么 | 不能代替什么 |
| --- | --- | --- | --- |
| capability | 一个稳定的用户结果或可独立维护的领域能力；编号和 F cluster 以本文为准 | action 的唯一业务 owner 和后续迁移边界 | 不能因为文件同目录、页面相邻或词语相似而吸收 action/source |
| surface | 用户或系统能够触发 intent 的交互区域，例如页面、对话框、菜单、快捷键或 background trigger | action 的入口位置、入口类型和可发现性 | 不能仅凭 Screen/Composable/shortcut/job 声明证明 action 已执行 |
| container | 承载 surface 的 Screen、Tab、Dialog、Composable、导航目的地或 DI/wiring 容器 | 布局/导航存在，以及生产入口可能位于何处 | 不是 action，也不能代替 handler、effect、持久化、确认或反馈 |
| action | 一个原子 intent；执行后必须发生领域状态转换、导航、外部 effect、持久化变化或用户可见终态之一 | 要迁移、比较和验收的最小行为单位 | 文案、控件、getter、默认值或容器控制不是 action；不同 effect 不得为减少条目而合并 |
| source entry | 一个经人工读取上下文后登记的 production 源码位置，机械部分包含 revision/platform/path/line/locator/declaration/kind/contextHash | 仅证明登记的 evidence role；抽象声明还必须配 concrete implementation 和 production consumer | 不能由候选分数自动决定 action/capability 归属，也不能仅凭存在性推出状态 |

每个 action 的人工记录必须先写 `ownerCapabilityId`、`surfaceId`、入口类型、intent、expected effect 和 observable result，再选择源码。每个平台/权威角色的 evidence chain 按实际行为登记 `ENTRY/TRIGGER`、`HANDLER`、`DOMAIN_EFFECT` 或 `PLATFORM_EFFECT`、`PERSISTENCE`、`CONFIRMATION`、`FEEDBACK`；不适用的 role 明确写理由，不能用无关条目填满字段。同一源码位置可以承担多个 role，前提是该表达式在 production context 中确实同时完成这些职责，并在人工理由中说明，而不是为满足计数重复引用。

实现状态只允许按以下规则人工决定：

- `PRESENT`：目标角色存在可追踪的 production 入口/触发器和实际 effect，且 action 要求的持久化、确认、反馈或终态均有证据；单独的容器、声明、getter 或文案永远不足。
- `PARTIAL`：production 链存在并执行目标 intent，但缺少一项已明确列出的语义、反馈、确认或持久化阶段；必须记录缺失项和 follow-up，不能用作“尚未看完”的临时状态。
- `GAP`：目标平台应支持该 action，但人工检索并检查预期消费者后确认不存在等价 production 链，或现有链语义不满足；必须写适用理由、检索边界和 follow-up，不能由“helper 没找到候选”自动产生。
- `EXEMPT`：平台约束使 action 确实不适用或不能可靠实现；必须写平台边界、用户可见的 unsupported/降级行为和重新复查条件。成本高、尚未实现、没有候选或 Desktop 与 Android 不同都不是豁免理由。
- `READER_OWNED`：只用于本文明确排除的 Reader capability 元数据；不得把 Reader 内部 source/action 混入非 Reader evidence graph，也不得用它掩盖允许边界内的非 Reader 入口缺口。
- `desktop-product` 是人工 migration tag，不是状态。它必须有 Desktop production 链；固定原版/当前 Android 不得伪造为 `PRESENT`，tracked 只记录真实引入/缺失事实。其他 migration tag 同样只能在 evidence chain 成立后描述架构归属，不能反向证明实现。

四方裁决顺序固定为固定原版 → 当前 Android 基线 → Desktop → tracked upstream。`UNCHANGED` 只在具体 action 的入口、effect 和可观察结果语义均未变化时成立；文件/locator 仍存在或 contextHash 相同都不是充分条件。移动、改名、拆分、多消费者、删除或语义改变分别记录实际 disposition 和新证据，不得用文件级默认结论覆盖。

跨 capability/source 关系遵循单一归属默认：action 只有一个 owner capability；source entry 默认也只服务该 capability。确有共享 executor/adapter 时，必须为每个额外 capability 登记其 production consumer path/locator、承担的 evidence role 和人工共享理由，且双向引用完全一致。通用容器、工具类、repository getter、CookieJar/browser adapter 或同名 symbol 不能作为共享理由；缺少任一消费者路径时关系无效。测试只校验这些人工声明的结构和明确禁区，不通过 token、synonym、评分、action-ID 特判或 digest 推断业务关系。

#### `NR0-01.C` 独立负例门禁记录

- RED：`nr0-01-c-red` 在 `compileTestKotlinJvm` 因独立 `ManualInventoryEvidencePolicy`/数据模型尚不存在而失败，未读取旧 manifest，也未调用 helper；失败原因与预期一致。
- GREEN：`nr0-01-c-green` 运行 `NonReaderEvidencePolicyTest`，JUnit XML 为 `14 tests / 0 skipped / 0 failures / 0 errors`。13 个动态测试逐项覆盖本文 12 类错误（Desktop product 固定原版伪 PRESENT 与自动 EXEMPT 分开验证）；一个正例测试内含普通分享、设置 UI→effect→persistence、以及具有 consumer path/locator/reason 的共享 executor 三条合法链。
- 独立性：策略只比较人工填写的 scope、evidence kind、behavior contract、role、decision mode、consumer proof 和 tracked observation；不读取 action ID 词义，不包含 token/synonym/score/override，也不读取 240 万字节 manifest。`external.share → AndroidCookieJar` 固定哨兵已因 behavior contract 不一致被拒绝。

#### `NR0-01.D` 机械工具收缩记录

- 删除 `scripts/nr0_01_action_inventory_v2.py`：不保留自动 anchor、semantic group、synonym/token、候选评分、自动 GAP/EXEMPT、source graph 或 tracked disposition 的再次运行入口；后续机械事实用只读 Git 命令取得，人工填写关系。
- 将旧 82 KB/约 1300 行的 `NonReaderActionInventoryContractTest` 替换为 14.7 KB 结构契约，只验证 schema、固定 revision、确定性 source ID、引用存在/唯一/双向、production path，以及 Git blob 中的 line/locator/contextHash drift；不锁定 action/source 数量或语义 digest，不选择业务锚点。
- 审计 `NonReader*.kt` 已无 `ANCHOR_OVERRIDES`、semantic group、synonym、token matcher、candidate score、`SEMANTIC_MATCH`/`EXACT_OVERRIDE` 和语义 digest 常量。`nr0-01-d-focused-2` 运行独立负例与结构契约共 16 tests，全部通过；第一次 focused 暴露并修正了 `CONTAINER/EXEMPT` source 可以没有 `actionIds` 的合法结构假设，未增加语义例外。

#### `NR0-01.E` 20 个高风险先导样本记录

本表固定读取 `F=main@6fbf6df…`、`C=current-fork@95b82fc…`、`D=current-fork@95b82fc…` 的 Desktop production tree 和 `T=upstream/main@d7f3cee…`。它只记录 stop-gate 的人工方法审查，不是第二份 inventory 权威；旧 manifest 对应字段继续保持 provisional，下面的裁决必须在相应 F cluster 写回唯一 `parity-manifest.json` 后才能成为机器结论。

| 类别 | action | 四方 production 上下文 | 人工裁决与 source 关系 |
| --- | --- | --- | --- |
| 词法碰撞 | `external.share` | F `IntentExtensions.toShareIntent`；C `buildShareIntent` → shared `ExternalShare`；D `MangaDetailComponents.mangaLinkActions` → `DesktopShareService.share` → notification；T 与 F intent/effect 等价 | action 有效且 D 为 `PRESENT`，旧 `GAP` 作废；只关联分享入口、payload、native share 与 terminal feedback，`AndroidCookieJar`/browser adapter 全部拒绝 |
| 词法碰撞 | `general.incognito` | F `MoreTab` → `BasePreferences.incognitoMode` → history/privacy consumers；C 同链函数化；D `GeneralSettingsScreen`/`MoreRootScreen` → `DesktopAppPreferences.incognitoMode` → browse/progress/privacy consumers；T 与 F 等价 | action 有效；F/C `PRESENT`，D 经 F1 复核为 `PARTIAL`：持久化与 privacy consumers 存在，但缺少 Android 等价的持续状态指示和快捷关闭。旧 C `SourcePreferences` 与 D `SecurityPreferences` 锚点不能证明全局 incognito |
| 词法碰撞 | `app-language.open` | F/C/T `SettingsAppearanceScreen` 的 `navigator.push(AppLanguageScreen())`；D `AppearanceSettingsScreen` 的 `showLanguageDialog = true` | action 有效且三端 `PRESENT`；旧 D 锚点是关闭/Cancel 对话框，必须替换；T 只是周边 collection/context 改动，打开动作语义未变 |
| 词法碰撞 | `advanced.clear-network-cache` | F/C/T 只有 Android WebView `clearCache(true)`，不是 OkHttp/network disk cache；D 按钮 → confirmation → `ProductionAdvancedSettingsPlatformActions.clearNetworkCache` → snackbar | `desktop-product/PRESENT` 有效，但旧 D 接口默认 `false` 是禁止证据；必须绑定 concrete implementation、production caller、确认和成功/失败反馈，Android 三方保持不适用而非伪 `PRESENT` |
| 危险/通用控制 | `history.clear` | F/C `HistoryDeleteAllDialog.onDelete` → `HistoryScreenModel.removeAllHistory` → `RemoveHistory.awaitAll`；D `HistoryTab` confirmation → `HistoryScreenModel.clearAllHistory`；T effect 移到 `HistoryViewModel` | action 有效且三端 `PRESENT`；Cancel/dismiss 不入证据。T 结论应为实现移动且语义保持，不是仅凭未变 dialog 宣称整个 action `UNCHANGED` |
| 危险/通用控制 | `categories.delete` | F/C `CategoryScreen` confirmation → `CategoryScreenModel.deleteCategory` → `DeleteCategory.await`；D `CategoryManagementDialog` → `LibraryScreenModel.deleteCategory`；T `CategoryViewModel.deleteCategory` | action 有效且三端 `PRESENT`；旧 fixed effect 可保留为链中一环，但 T `PATH_MISSING` 作废，应登记 ScreenModel → ViewModel 移动和真实消费者 |
| 危险/通用控制 | `extension-repo.delete` | F `ExtensionReposDialogs` confirmation → `ExtensionReposScreenModel.deleteRepo` → `DeleteExtensionRepo`；C 加入 service result；D `DeleteRepoDialog` → actions.delete → shared use case；T Repos 重命名/迁移到 Stores/ViewModel | action 有效且三端 `PRESENT`；F/D 的 dialog 函数声明本身无效，需入口、effect、结果链；T 是命名/容器迁移，不是行为删除 |
| 危险/通用控制 | `downloads.pause-all` | F/C queue UI → `DownloadQueueScreenModel.pauseDownloads` → `DownloadManager`；D queue UI → `DownloadQueueScreenModel.pauseAll` → `DesktopDownloadManager.pauseAll` 并由 `isPaused` 反馈；T effect 移到 `DownloadQueueViewModel` | action 有效且三端 `PRESENT`，可逆操作无需危险确认；旧 effect 锚点可作为一环，但 T `PATH_MISSING` 必须改为移动且语义保持，接口默认值不能代证 |
| background/shortcut/settings | `library-update.schedule` | F `SettingsLibraryScreen` → `LibraryUpdateJob.setupTask` → WorkManager；C 加入 `BackgroundTaskLifecycle`；D runtime → `LibraryUpdateScheduler.start` → task scheduler/timer；T 与 F 任务语义等价 | action 有效；声明 job/函数不够，必须包含设置 consumer、注册/取消和运行时启动链。source 不与 run-now/cancel 自动共享 action 归属 |
| background/shortcut/settings | `global-search.keyboard-submit` | F/C `AppBar.searchAndClearFocus` 同时被 Enter/IME 调用并执行 `onSearch`；D `GlobalSearchScreen.onKeyEvent` 消费 Enter 并触发 search；T 与 F 等价 | action 有效且三端 `PRESENT`；shortcut source 必须同时包含按键 consumer 与 search handler，不能只登记 `onKeyEvent`/shortcut 声明 |
| background/shortcut/settings | `download-settings.concurrent-sources` | F/C/T slider → `DownloadPreferences.parallelSourceLimit`；Android `Downloader` 订阅 `changes()`；D baseline 无该 preference、UI 或 executor consumer | Android action 有效，D 为真实 `GAP` 而非 `EXEMPT`；旧 F/C 标题行不足，需 UI mutation + persistence + production consumer，D 不得关联无该字段的下载页面 |
| background/shortcut/settings | `tracking-settings.auto-update-on-mark-read` | F/C/T ListPreference → `TrackPreferences.autoUpdateTrackOnMarkRead` → Manga model consumer；D 只有不同语义的 boolean `autoUpdateTrack` UI/consumer，没有该 enum policy | Android action 有效，D 为真实 `GAP`；不能用同组 tracking 设置或 Reader 的不同 boolean consumer冒充。T 行号/context 变化但 intent/effect 保持 |
| Desktop/platform/Reader | `window-privacy.enable` | F/T secure mode + incognito flow → `Window.setSecureScreen`；C 通过 `AndroidSecureScreenConsumer` 接线；D `DesktopSecureScreenSettings` → controller → `DesktopWindowPrivacy.apply`/native bridge → explicit feedback | action 有效且三端 `PRESENT`；旧 source graph 中 `HistoryScreenModel` 无关，必须替换为 settings/lifecycle/controller/bridge 链；共享 incognito preference 要写实际 consumer |
| Desktop/platform/Reader | `system-widget.exempt` | F/C/T `LockedWidget` 是“锁定时点击打开主应用并显示不可用文案”的真实 Android widget 行为；D 无 system widget provider，但 `DesktopPrivacyCapabilities` + `SecuritySettingsScreen` 显示 unavailable 边界 | capability 的 `PLATFORM-EXEMPT` 有真实用户反馈和复查条件，但 `system-widget.exempt` 不是用户 intent，作为 action 删除；widget source 只保留 capability/platform 边界证据 |
| Desktop/platform/Reader | `library.shortcut-range-select` | F/C/T 无 Shift-click 对应动作；D `ShiftAwareClickModifier` → `LibraryTab.handlePrimaryClick` → `LibrarySelectionState.selectRange` | 合法 `desktop-product/PRESENT`；旧 effect 行可保留，但必须补真实 Shift consumer；与 toggle-select 共享同一 state 时逐个写 consumer，不向无关 library actions 扩散 |
| Desktop/platform/Reader | `reader-settings.persist` | F/C/T `SettingsReaderScreen` 内含多个独立 preference mutation；D `ReaderSettingsScreen` 又包含 default mode、RTL、prefetch 等不同持久化链 | 旧 generic action 粒度无效，应在 F1 删除/拆成可验收的设置入口与独立 mutation；只覆盖非 Reader 设置边界，不收录 Reader page/progress/loader 内部 source |
| tracked 移动/多消费者 | `sources-state.observe` | F `GetEnabledSources.subscribe` → `collectLatestSources`；C 加 shared reducer；D `DesktopSourcesScreenModel.observe` combine → reducer；T `SourcesScreenModel` 移到 `SourcesViewModel` | action 有效；T `PATH_MISSING` 作废，登记移动且语义保持。source 关系需包含 subscription、reducer 与各端 consumer，不能只绑容器类 |
| tracked 移动/多消费者 | `network.request` | F/C shared `newCachelessCallWithProgress` 创建实际 Call；D 复用 common 实现且需具体 Desktop consumer；T 函数改成多行签名，updater/HttpSource consumers 仍存在 | action 有效；T 不是删除而是 signature/context change with same effect。跨 capability 只在列出 updater/HttpSource 等实际 consumer 时共享，不因都使用 OkHttp 自动关联 |
| tracked 移动/多消费者 | `app.startup` | F/C/T 实际入口是 `App.onCreate` 及其 module/lifecycle 初始化；D 是 owner election → `prepareDesktopOwner` → runtime/window lifecycle | action 有效且 D 为 `PRESENT`；旧 F/C `WidgetManager.init` 只是一项 startup consumer，不能代表 app startup，旧 D `GAP` 与 widget source graph 均作废 |
| tracked 移动/多消费者 | `categories.rename` | F/C dialog `onRename` → `CategoryScreenModel.renameCategory` → `RenameCategory.await`；D `CategoryManagementDialog` → `LibraryScreenModel.renameCategory`；T effect 移到 `CategoryViewModel` | action 有效且三端 `PRESENT`；旧 F/C dialog 声明是 container 证据，需换为 handler/effect；T 记录实现移动且语义保持，不以 contextHash 变化直接判业务改变 |

先导结论：20/20 都能在四方 production context 中得到明确判定；18 个 action identity 可保留但其中多数要重选 evidence/status/tracked/source，`system-widget.exempt` 与 generic `reader-settings.persist` 两个伪 action 必须删除/拆分。所有旧误配均被 B/C 的既有规则拒绝，没有新增 action-ID 特判、override、exception 或词表；新方法未出现两例同类无法解释的系统性失败。主协调者复核后重跑 `nr0-01-e-stop-gate`，14 tests 全绿，因此 E 为 `PASS`。

#### `NR0-01.F1` 平台基础 cluster 记录

- 范围固定为 capability `3,4,7,8,9,10,11,12`。人工将 25 个 provisional actions 收敛为 24 个：capability 3/4/7/8/9/10/11/12 分别为 `4/2/5/2/1/4/3/3` 项；删除与 composition root 无关的 Home tab 动作和 generic `reader-settings.persist`，把 source feedback 降回 action 的 evidence role，并补入真实 pin/disable/retry intent。
- Reader 边界只有 `reader-settings.open`，scope 为 `READER_BOUNDARY` 且 migration tag 为 `READER-OWNED`；没有收录 Reader 内部设置持久化、页加载或阅读进度。`sources-state.retry`、`crash.open-log` 是有 Desktop production 链的 `DESKTOP-PRODUCT`，固定原版/当前 Android 均保持人工 `GAP`，没有伪造 `PRESENT`。
- Desktop 实际缺口写入后续任务：`onboarding.complete` → `SE-01`；`general.incognito` 的持续状态反馈 → `SE-01`；library update constraints/shared executor → `LU-01`；notification open/dismiss 和 crash export → `PA-01`。Android 显式 shutdown 以 OS-owned lifecycle 人工 `EXEMPT`，同时记录复查条件。
- F1 manifest 使用 `schemaVersion: 2` 和逐 authority role 的 `decisionMode: MANUAL`/evidence 数组。tracked diff 与 source graph 明确保持 `PENDING_NR0-01.G`；F1 替换 source owner 后，其他尚未迁移 cluster 的旧跨 capability source 引用继续视为 provisional，结构测试只核验同一旧 capability 内仍闭合的关系，不恢复旧误配，也不把混合迁移期引用当作完成证据。
- RED：`nr0-01-f1-red` 编译成功后 5/5 因旧 F1 schema、单锚点 evidence 和 Reader scope 失败。GREEN：`nr0-01-f1-green-2` 运行 `NonReaderActionInventoryContractTest` 与 `NonReaderEvidencePolicyTest` 共 `19 tests / 0 failures`；3 个 cluster mutation 分别拒绝自动 authority decision、删除必需 production evidence role、以及 `READER_INTERNAL` scope。24 actions 的 72 个 authority role 全部为 `MANUAL`，精确 Git line/locator/contextHash drift 检查和 `git diff --check` 通过；没有保留一次性合并脚本。

F2 开始前机械计数得到 95 个旧 provisional actions，触发单 cluster 最多 60 actions 的强制停止条件。任务内固定拆为 F2.1 capability `16,17,19`（旧 provisional 38 项）和 F2.2 capability `22,24,26`（旧 provisional 57 项）；两段分别执行 3 个 mutation 与 focused stop-gate，总 timebox 调整为 2–4 日。此拆分只改变内部 checkpoint，不增加计划文件、提交或自动删减规则。

#### `NR0-01.F2.1` Category/Library/批量动作 cluster 记录

- 范围固定为 capability `16,17,19`。人工将 38 个 provisional actions 收敛为 32 个，三组分别为 `4/21/7` 项；删除 `library.refresh-category`/`library.refresh-all`（归 capability 10 的 `library-update.run-now` 所有）、不存在于 Library surface 的 `library.open-upcoming`/`library.open-authors`，以及只作为 `select-range`/`select-one` 输入证据的两个 shortcut 伪 action。
- Category CRUD 三端均有真实共享 interactor 链；`categories.delete` 的 fixed/current Android 有确认，Desktop 当前从行操作直接删除，因此不是 `PRESENT`，而是 `PARTIAL → LB-01`。Library 的搜索、切换 category、筛选、排序、显示、随机、详情跳转和五种选择状态均重新绑定具体 handler/effect/feedback；不使用 Screen/Dialog 声明、通用按钮或旧 manifest 错误锚点代证。
- `library.continue-reading` 只记录 Library 的入口、下一章请求与 Reader 导航边界，scope 为 `READER_BOUNDARY`、migration tag 为 `READER-OWNED`，未收入 Reader 页加载/进度/内部设置。`library.open-global-search` 的 Android Library 入口存在，但 Desktop 只有其他 surface 的 Global Search；旧“详情标签搜索即 Library 入口”结论作废，记录为 `GAP → BR-01`。
- Library settings 的 Desktop 缺口分别为 default category、categorized display、duplicate-read policy → `LB-01`；Android chapter swipe start/end 在 Desktop 因不存在 swipe gesture surface 人工 `EXEMPT`，并写明新增 swipe surface 时重审。hide-missing 有 Desktop 设置持久化和 Manga Detail production consumer，因此为 `PRESENT`。
- 批量分类、已读、未读、下载、迁移三端均有 production 链。Android selection dialog 支持确认后删除下载或移出书架；Desktop selection bar 没有删除下载动作，记为 `GAP → DL-02`，而移出书架有真实 mutation 但无确认，记为 `PARTIAL → LB-01`。三项破坏性 action 均声明 `risk: DESTRUCTIVE`、`confirmation: REQUIRED` 和 `CONFIRMATION` evidence role。
- RED：`nr0-01-f2-1-red` 共 8 tests，其中 4 项因旧 F2.1 schema、缺少手工 evidence role/risk 与 mutation 选择正确失败。GREEN：`nr0-01-f2-1-green` 运行 `NonReaderActionInventoryContractTest`（8 tests）与 `NonReaderEvidencePolicyTest`（14 tests），合计 `22 tests / 0 skipped / 0 failures / 0 errors`；3 个 F2.1 mutation 分别拒绝 capability owner 漂移、破坏性 action 缺少确认，以及把容器证据冒充 production action effect。32 actions 的 96 个 authority role 全部为 `MANUAL`，精确 Git line/locator/contextHash 检查通过；一次性合并脚本已删除。

#### `NR0-01.F2.2` 详情 membership/chapters/cover cluster 记录

- 范围固定为 capability `22,24,26`。人工将 57 个 provisional actions 收敛为 55 个，三组分别为 `6/44/5` 项；删除 `manga.viewer-mode` 与 `manga.reader-direction`，因为两者的权威 consumer 位于 Reader 内部设置/持久化链。只保留 `manga.continue-reading` 与 `manga.open-chapter` 两个 `READER_BOUNDARY`/`READER-OWNED` 打开请求，未收入 Reader 页加载、进度或内部设置。
- Manga membership 的添加、选择分类、编辑分类与移出书架三端均有 production 链；Desktop 缺少重复项复核和移出书架后的独立下载清理选择，分别记录为 `GAP → MD-03`。下载清理是破坏性 action，固定原版/当前 Android 的 Snackbar action、确认分支与文件删除链均已取证，Desktop 不再以单章下载删除对话框冒充该动作。
- Chapters cluster 重新确认 Desktop 已有数字/日期/字母排序、单选、标记此前已读、Tracking、作者/画师发现等链；三个过滤器因只有瞬时 boolean、缺少 Android tri-state 与 manga flag 持久化而为 `PARTIAL → MD-02`。默认设置三项、范围选择、反选、详情描述展开、标题搜索和 source 搜索为真实 `GAP`；批量删除下载有实际 effect 但绕过单章确认，记为 `PARTIAL → MD-05`。`manga.mark-all-read` 是有确认、持久化和可观察结果的 `DESKTOP-PRODUCT/PRESENT`，Android 两个权威角色保持人工 `GAP`。
- Cover cluster 的查看、保存、分享在 Android 有完整 viewer/image saver/share 链而 Desktop 为 `GAP → MD-06`；Desktop 现有文件选择 adapter → custom-cover 更新 → feedback，以及删除 override → source cover/feedback 链，使 `cover.edit`、`cover.delete` 均为 `PRESENT`。删除 custom cover 按三端实际语义恢复 source cover，不伪造不存在的确认要求。
- 最终机器状态为 `38 PRESENT / 4 PARTIAL / 13 GAP`，两项破坏性 action 均声明 `risk: DESTRUCTIVE`、`confirmation: REQUIRED` 与 `CONFIRMATION` evidence role。55 actions 的 165 个固定原版/当前 Android/Desktop authority role 全部为 `MANUAL`；tracked diff 与 source graph 继续明确保持 `PENDING_NR0-01.G`。
- RED：`nr0-01-f2-2-red` 运行 25 tests，5 项因旧 F2.2 schema、缺少手工 evidence 数组、Reader boundary/risk 和 GAP search boundary 正确失败。GREEN：`nr0-01-f2-2-green` 运行 `NonReaderActionInventoryContractTest`（11 tests）与 `NonReaderEvidencePolicyTest`（14 tests），合计 `25 tests / 0 skipped / 0 failures / 0 errors`；3 个 F2.2 mutation 分别拒绝 Reader boundary migration 漂移、破坏性详情 action 缺少确认、以及 GAP role 缺少人工检索边界。精确 Git line/locator/contextHash drift 检查通过，一次性合并脚本已删除。
- 暂停点：F2.2 完成后按用户要求暂停。`NR0-01.F3` 保持 `TODO`，本轮没有读取或生成 F3 语义结论；NR0-01 仍是 A–H 单一原子批次，因此没有为本 checkpoint 创建中间提交。

如果任一 F cluster 超过 60 个 action，必须先在本表按 surface/动作族拆成 `F<n>.1/F<n>.2`，更新 timebox 后再开始；不得边审查边扩大 cluster。

#### Checkpoint 具体执行约束

- `A` 只做止损和分级，不修语义。现有 415/458 条目允许保留路径、行号、entry kind、revision、locator/context hash 等机械候选；自动 action anchor、capability/source 关系、状态和 tracked 结论全部降级为待人工重做。不得为了保住既有数量而维持错误条目。
- `B` 先手写规则和 12 类禁止项，再允许修改 schema。action 是会引发领域状态、导航、外部 effect 或用户可见结果的 intent；surface/container 不是 action；background/shortcut 是入口类型，不因被发现就自动等于某项用户能力。
- `C` 使用最小独立 fixture，不加载 240 万字节完整 manifest，不调用 helper。错误必须有可定位的断言消息；若 external.share → AndroidCookieJar 仍能通过，禁止进入 `D/E`。
- `D` 以“删除语义代码”为主，不继续修补。helper 可发现候选，但人工选择哪一条证明哪个 action；结构测试可以检查人工关系的引用一致性，不能自行生成期望关系。
- `E` 的 20 个样本由执行者显式列出，不随机抽样且不由 helper 选择；五类各 4 个：词法碰撞、危险动作/通用控制、background/shortcut/settings、Desktop product/platform/Reader 边界、tracked 移动/改名/多消费者。每项读取固定原版、当前 Android、Desktop 和 tracked-upstream 上下文。完成后立即做一次主协调者只读方法审查；发现系统性缺陷即把 `E` 标为 `INVALIDATED` 并回到 `B/C/D`，不追加规则继续扩展。
- `F1–F8` 严格一次只做一个 cluster：机械列候选 → 人工确定 action/owner/source → 添加 3 个针对该 cluster 的破坏性 mutation → focused 验证 → 状态改为 PASS。前一 cluster 未 PASS，不得生成下一 cluster 的语义字段。
- Reader capability `43,44,45,47,49,51,53,54` 不进入 F cluster；只验证 `READER_OWNED` 元数据和本文第 2.3 节允许的入口边界，不盘点 Reader 内部 action。
- `G` 只能在语义 action 已通过后进行。tracked 结论比较具体 action context，不以“文件仍存在”“词语仍出现”代替；source graph 默认禁止跨 capability，共享 source 必须写实际多消费者路径和人工理由。
- `H` 才允许锁定最终计数/digest。415 actions、458 entries 不是保底指标；删去伪 action 或重新归属后数量变化是正确结果。独立审查从每个 cluster 抽取高风险项并重跑负例；修复复审仍遵守最多一轮。既有两轮 CHANGES_REQUIRED 只作为已废弃自动语义方法的历史，不冒充新方法审查；本次用户要求依据报告重规划后，修正版批次重新执行一轮独立审查和至多一轮修复复审。

- Focused 验证：独立负例 fixture、manifest schema/reference/drift、逐 cluster mutation、固定 Git blob/ref、Reader scope、`spotlessCheck`、`git diff --check`。
- 关闭条件：全部 checkpoint PASS；helper 和测试均无自动语义 matcher；零自动语义豁免、零未审查跨 capability 绑定；每个 GAP/EXEMPT/desktop-product 有人工理由和 follow-up/边界；审查者可从每个 action 追到真实 production 上下文。
- 预计：15–25 工程日，约 4–7 个 manifest/helper/test/doc 文件但会显著超过 400 行。内聚原因是最终 manifest schema 和双向关系必须原子提交；风险由小样本、cluster stop-gate 和零自动语义规则控制，而不是由更多 override 控制。

### `NR0-02` 建立可执行复用架构门禁

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`NR0-01`。
- `NR0-02.A`（0.5–1 日）：只选 `NR0-01.F2` 中一个已经人工通过的详情 action，手写四个最小编译变异：“shared core 无 Android 消费者”“Desktop 绕过 use case 直连 repository”“adapter 内复制业务判断”“legacy executor 仍可从 production 到达”；四个必须以不同断言 RED。
- `NR0-02.B`（1–2 日）：只为该 action 建立 compiled edge、production factory 和真实行为测试；确认恢复任一错误变异都会失败，且源码扫描本身不被当作功能完成证据。
- `NR0-02.C`（1–2 日）：按 `F1–F8` cluster 扩展结构规则；一次只增加一个 dependency rule/allowlist family，每项允许名单必须写平台原因、真实消费者和复查/删除条件。
- GREEN：在现有 architecture/parity tests 中增加 compiled edge 与 production factory 约束；不得调用 NR0-01 helper 生成预期边，测试也不得用类名/关键词相似代替编译关系。
- 规则：源码扫描只用于包依赖、禁用 API 和遗留 symbol 守卫；完成证据必须绑定真实类、DI、数据库、HTTP 或 mounted UI。若同一规则需要超过 5 个 allowlist 例外，停止扩展并重新检查依赖边界。
- 用户行为：无新增 UI；保护所有后续迁移不会只增加一个未使用的 shared facade。
- 验证：architecture/parity focused tests、mutation fixture、Spotless、`git diff --check`。
- 关闭条件：A/B/C 均 PASS；断开任一已声明的 Android/Desktop shared consumer，或恢复一个已删除 executor，测试都会失败；允许名单无无理由或自动生成条目。
- 预计：3–5 工程日，约 4–8 个测试/fixture 文件。

### `RS-01` 提取唯一章节同步核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `N/A 待记录` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`NR0-02`。
- 权威：固定/跟踪上游 `eu.kanade.domain.chapter.interactor.SyncChaptersWithSource`；冻结空列表、URL 去重、sanitize、source prepare、章节号识别、新增/更新/移除、download rename、read/bookmark/dateFetch 保留、duplicate-read、excluded scanlator、fetch interval 和 last update 语义。
- RED：为上述每个分支建立共享契约测试和数据库集成 fixture；下载重命名、source-specific prepare 与 clock 通过 port fixture 验证。
- GREEN：以原版方法体为谱系提取纯同步 planner/executor 到 KMP/shared，Android 下载、source prepare、clock 与持久化只作为 adapter；不要根据 Desktop 现状另写简化算法。
- Android：当前 `SyncChaptersWithSource` 成为薄入口或直接消费共享 core，原版详情、LibraryUpdate、Metadata、DeepLink、Migration 现有行为不变。
- Legacy：Android 内重复决策降为 adapter；Desktop 重写在 `RS-03/LU-01` 到期，当前任务记录临时双轨清单。
- Focused 验证：shared contract、data integration、Android production wiring、download rename fixture、Spotless。
- 关闭条件：共享核心不引用 Android/Compose/file API；绕过它后 Android 同步测试失败。
- 预计：4–7 工程日，约 8–14 个文件/超过 400 行；同步事务与第一生产消费者不可拆开。

### `RS-02` 提取统一远端作品更新核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `N/A 待记录` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RS-01`。
- 权威：tracked-upstream `mihon.domain.source.interactor.UpdateMangaFromRemote`、`RemoteMangaUpdate` 及其 `MangaViewModel`、LibraryUpdate、Metadata、DeepLink、Migration 调用方。
- RED：details-only、chapters-only、合并请求、stub/missing source、title update preference、空 cover、custom cover、local source、download directory rename、manual fetch、typed failure 与 cancellation。
- GREEN：从上游提取 remote update coordinator；source call、cover cache、download rename 和 I/O dispatcher 变为 ports，manga/chapter 更新与返回模型保持上游语义。
- Android：所有当前 Android 调用方切到同一 coordinator；不得保留详情和后台更新各自组合 `getMangaDetails/getChapterList` 的路径。
- 用户行为：Android 页面无 UI 变化；刷新详情和章节时错误/空数据/封面处理与较新上游一致。
- Focused 验证：shared coordinator、Android caller wiring、MockWebServer 成功/空/403/429/500/畸形响应、DI resolution。
- 关闭条件：Android 所有 remote materialize 调用均可追到同一 core；旧组合器已删除或仅为 port。
- 预计：3–5 工程日，约 7–12 个文件。

### `RS-03` 切换 Desktop materialize/刷新链

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RS-02`。
- RED：Desktop Browse → 详情、Global Search → 详情、外部链接/deep link → 详情、详情手动刷新均通过 production factory 执行 shared coordinator；覆盖未收藏作品首次落库、重复打开、partial source data、错误/重试和取消。
- GREEN：实现 Desktop Source/Cover/Download adapters；将详情和搜索入口切换到 shared remote update，不让 UI 或 ScreenModel 自行拼装 `SManga`/chapter writes。
- UI：加载、空章节、源不可用、网络限制和重试反馈明确；导航对象类型与 Voyager 上下文有实例化/类型测试。
- Legacy：删除 `SaveSourceMangaForDetails` 及等价详情 materializer；若 `LibraryUpdateChecker` 尚待 `LU-01`，只允许它保留在后台更新入口且登记截止任务。
- Focused 验证：Desktop DI、Screen/navigation、MockWebServer、数据库集成、mounted detail refresh、Spotless。
- 关闭条件：断开 Desktop adapter 或 shared coordinator 会使四类入口测试失败；不再有第二个详情同步器。
- 预计：4–6 工程日，约 8–14 个文件/可能超过 400 行；四个入口与 legacy 删除构成一个 cutover。

### `MD-01` 提取详情观察、刷新和 processed chapter 状态核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RS-03`。
- 权威：原版 `MangaScreenModel.State.Success`、chapter item projection、download state observation、refresh/error/event 语义；UI-specific `SnackbarHostState` 不进入共享层。
- RED：manga/chapter reactive update、missing/removed rows、download queued/downloading/downloaded/error、refresh in-flight、source missing、空章节、一次性 error/result effect。
- GREEN：从 Android state processing 提取 immutable `MangaDetailSnapshot`/projector/reducer 或等价纯核心；两端 ScreenModel 只收集 Flow、转发 intent 并呈现 effect。
- Desktop UI：详情 skeleton/content/empty/error/retry 保持稳定；刷新按钮禁用/进度和失败反馈可见。
- Legacy：删除 Desktop 中重复的 processed chapter、下载态合并和 refresh flag 决策；Android 同类纯判断也改为共享调用。
- Focused 验证：shared reducer contract、Android production consumer、Desktop factory/DI、mounted UI state/effect tests。
- 关闭条件：同一 fixture 在 Android/Desktop 产生同一领域 snapshot；平台层只映射 UI 文案/组件。
- 预计：3–5 工程日，约 7–12 个文件。

### `MD-02` 共享章节过滤、排序、显示、扫描组及详情设置

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-01`。
- RED：unread/downloaded/bookmarked 三态 `INCLUDE/EXCLUDE/OFF`、scanlator exclusion、source order/chapter number/upload date 排序与方向、display mode、set current as default、apply to existing、reset defaults、fetch interval、每本漫画的 viewer mode/direction flags，以及 local source 例外。
- GREEN：直接复用 `SetMangaChapterFlags`、`SetMangaDefaultChapterFlags` 与原版 filtering/sort projection；缺少的纯规则从 Android 提取，不在 Desktop 增加平行 flags。
- Desktop UI：过滤菜单提供三态反馈、active indicator、扫描组选择、默认/重置、fetch interval 和每本漫画阅读模式/方向入口；批量应用到现有作品必须确认范围并显示成功/失败结果。Reader 如何执行 viewer flags 仍由 Reader roadmap 验收。
- 持久化：关闭/重启详情后保持设置；数据库更新失败不能让 UI 假装成功。
- Legacy：删除 `setFilterShowRead/Unread/Bookmarked/Downloaded` 等仅内存布尔决策，或将其降为共享 intent 的薄映射。
- Focused 验证：flags/use case tests、数据库 persistence、双端 state projection、mounted filter dialog、DI wiring。
- 关闭条件：过滤、排序和后续继续阅读/下载使用同一 processed chapter 集合。
- 预计：4–6 工程日，约 8–13 个文件。

### `MD-03` 收藏、重复项、分类和移除清理

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-01`。
- RED：加入书库前 duplicate 检测、无分类/默认分类/总是询问、修改分类、移出书库、有下载时的保留/删除/取消确认、事务失败回滚与可见反馈。
- GREEN：组合 `GetDuplicateLibraryManga`、`UpdateLibraryMembership`、`GetCategories`、`SetMangaCategories` 和共享 removal policy；保留当前 Fork 原子 membership 事务作为增强。
- Desktop UI：收藏按钮、分类对话框、duplicate 跳转/确认、移除下载确认均有键鼠可达入口和一次性结果反馈。
- Android：当前详情入口消费同一 membership/removal decision，不退回原版的非原子部分更新。
- Legacy：删除两端 ScreenModel 内重复 default-category、duplicate 和 download cleanup 分支。
- Focused 验证：domain/data transaction、Android ScreenModel wiring、Desktop mounted dialogs/navigation、download cleanup adapter。
- 关闭条件：收藏状态、dateAdded 和分类要么全部提交要么全部回滚；取消移除不改变数据。
- 预计：3–5 工程日，约 6–10 个文件。

### `MD-04` 统一继续阅读与批量下载目标选择

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-02`。
- RED：继续阅读尊重 processed filters、漫画排序、未完成状态、download preference 与 `skipFiltered`；批量下载的 next N/unread/bookmarked/all 尊重同一排序、过滤、已下载和已排队状态。
- GREEN：从原版 `getNextUnreadChapter/getUnreadChaptersSorted/runDownloadAction` 提取纯 target selector；Android 与 Desktop 都只传 snapshot 和 policy。
- Reader 边界：结果仅为 `ReaderOpenRequest`/chapter id；不测试 session、页加载、跨章或阅读进度。
- Desktop UI：主阅读按钮、章节行打开、批量下载菜单入口完整；无可读/可下载章节时给出明确反馈，非收藏作品需要加入书库时沿用共享确认策略。
- Legacy：删除 Desktop 以 raw `sourceOrder` 或未经 processed filters 的列表选目标的逻辑。
- Focused 验证：shared selector fixtures、Android/Desktop production wiring、菜单 mounted test、Reader request schema contract。
- 关闭条件：正序/倒序、过滤开关和队列状态组合在两端得到同一 chapter ids。
- 预计：3–5 工程日，约 5–9 个文件。

### `MD-05` 补齐选择与批量章节动作

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-01`、`MD-02`。
- RED：single/range/select all/invert/clear、mark read/unread、mark previous read、bookmark/unbookmark、download/delete/cancel；批次中单项失败继续并报告成功/失败数。
- GREEN：从原版 selection range 与 action dispatch 提取纯 reducer，复用 `BatchUpdateChapters`；平台只映射 long-press/swipe 或 Shift/Ctrl/右键/快捷键。
- Desktop UI：保留 checkbox，同时提供与原版 range/invert 等价的键鼠入口；删除下载等危险动作先确认，运行中显示进度/禁用冲突动作，完成显示结果。
- Android：现有 swipe/selection UI 消费同一 reducer 和 batch result，不强制使用 Desktop 输入方式。
- Legacy：删除 ScreenModel 内独立 selected-id/partial-failure 决策；平台仅持有焦点和手势瞬时状态。
- Focused 验证：selection reducer、batch partial failure、双端 wiring、Desktop keyboard/mouse mounted tests、危险确认。
- 关闭条件：对同一 processed list 和 intent，两端产生相同 target ids/action result。
- 预计：4–6 工程日，约 8–14 个文件/可能超过 400 行；选择状态与动作必须同批可用。

### `MD-06` 补齐封面、描述、元数据、笔记与来源搜索

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-01`、`RS-03`。
- RED：cover view/save/share/edit/delete、picker cancel、permission/storage failure、cache invalidation；description expand/collapse、Markdown/link/image fallback、text selection；title/author/artist/tag/source search；notes view/edit/delete。
- GREEN：继续复用 `UpdateCustomCover`，把 save/share/search/navigation 输出为 platform-neutral effects；Markdown/metadata model 从原版 presentation 语义提取，renderer 保持平台化。
- Desktop UI：点击封面进入查看器，提供保存、复制/分享、编辑、删除；描述可完整展开和选择；作者/画师/标签/来源入口有鼠标与键盘反馈；笔记危险删除需确认。
- Desktop 增强：保留现有作者页、作品关系、普通文件路径和右键能力；通过额外 action decorator 接入，不改变共享原版动作。
- Legacy：删除 UI 内直接文件写入、搜索 URL 拼接和仅六行文本的功能限制；文件选择/系统分享仍是 adapter。
- Focused 验证：cover use case、platform effect adapter、navigation type、mounted detail/cover/notes tests、文件失败与取消。
- 关闭条件：原版详情头部与元数据区动作矩阵无缺项；平台不支持系统分享时仍提供复制/保存并明示降级。
- 预计：4–7 工程日，约 9–16 个文件/超过 400 行；可按“封面”和“元数据/笔记”两个无共享文件冲突的连续批次提交，但任务最终统一 checkoff。

### `MD-07` 详情页最终接线与全页门禁

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-02`–`MD-06`、`MG-02`、`TR-02`。
- RED：以动作级 inventory 挂载真实 `MangaDetailScreen`，逐项触发刷新、收藏/分类、过滤、继续阅读请求、选择、批处理、下载、追踪、迁移、封面、元数据、笔记和 web/share；断开任一 production dependency 必须失败。
- GREEN：收口 Desktop `MangaDetailScreenModelFactory`，保留平台 state holder，但所有业务决策来自 shared use case/reducer；Android 详情做同一调用图审计。
- UI：所有异步动作有 loading/success/failure；危险动作确认；空章节、源丢失、离线、无 tracker、无分类和部分失败均可恢复。
- Legacy：删除或降级 `MangaDetailScreenModel` 中剩余的第二套过滤、目标选择、批处理、迁移和 tracking 决策；不要求删除平台 ScreenModel 本身。
- 阶段验证：shared/domain、Android manga focused、Desktop detail tests、Screen/navigation/DI、Test Mode manga-detail subset、Spotless。
- 关闭条件：详情动作矩阵全部关闭；Reader 只以请求边界出现；原版 core 与 Desktop 增强的归属清晰。
- 预计：4–7 工程日，约 8–15 个文件，主要为 wiring、删除和集成测试。

### `LB-01` 复核书库与分类全部动作

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`NR0-02`、`MD-03`。
- 权威：原版 Library/Category 的搜索、tri-state filters、sort、display、分类 CRUD/reorder、选择、分类/已读/未读/下载/删除/迁移动作和错误语义。
- RED：用当前 `EvaluateLibrary`、category interactors、membership/batch use cases 建双端共享契约；缺任一菜单动作、空状态、失败反馈或 production wiring 时失败。
- GREEN：能 `SHARE-DIRECT` 的不再抽新层；只对仍位于 Android ScreenModel 的纯 selection/action policy 做谱系提取。
- Desktop UI：宽屏分类、键鼠多选与现有作者/Upcoming 入口保留；所有原版批量动作具有入口和危险确认。
- Legacy：删除 Library UI/ScreenModel 内重复 filter/sort/category/mutation 规则；平台仅保留 layout/navigation/input。
- Focused 验证：shared/data tests、Android Library/Category wiring、Desktop mounted library/category、导航和 DI。
- 关闭条件：inventory 中书库/分类动作全部有共享核心或明确 Desktop product/adapter 裁决。
- 预计：4–6 工程日，约 7–13 个文件。

### `LU-01` 提取并切换书库更新核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RS-02`、`LB-01`。
- 权威：固定/跟踪上游 `LibraryUpdateJob` 的 included/excluded categories、update restrictions、source grouping、fetch window、metadata/chapters、new chapter actions、partial failure、progress/terminal notification 和 cancellation。
- RED：无网络/计费约束通过 platform scheduler 测试；核心覆盖空书库、disabled/missing source、限制跳过、每源隔离、429/500、部分成功、取消、重复调度、auto-download/notification decision。
- GREEN：提取 `LibraryUpdatePlan/Executor/Result`；复用 `UpdateMangaFromRemote`，Android WorkManager 与 Desktop scheduler 只处理持久调度、生命周期和通知投递。
- Desktop UI：设置页提供与原版等价的分类和限制入口；手动更新显示进度、跳过原因、失败摘要和可重试结果。
- Legacy：删除 `LibraryUpdateChecker` 及 Desktop 独立章节写入；Android `LibraryUpdateJob` 降为 adapter，不再拥有同步规则。
- Focused 验证：shared executor、Android WorkManager production caller、Desktop scheduler/DI、MockWebServer、notification fallback、Test Mode update subset。
- 关闭条件：同一 update plan 在两端产生相同 eligible/skipped/result；平台只决定何时运行和如何通知。
- 预计：5–8 工程日，约 10–18 个文件/超过 400 行；完整 executor cutover 是单一用户能力。

### `DL-01` 提取下载资格、目标选择与动作结果核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RS-01`、`MD-02`、`MD-04`。
- RED：online/local、已下载、已排队、downloading、error、filtered、bookmarked、unread、next N、重复请求、仅 `QUEUED` 可取消等当前产品边界；批量删除/重试部分失败。
- GREEN：从原版详情/书库/Updates 的 download selection/action 规则提取共享 planner；queue/file/notification 是平台 ports，现有 Desktop 下载 manager 可继续作为 executor adapter。
- Android：Downloader/DownloadManager 调用方消费相同 eligible/action result，不迁移 Android 文件系统代码。
- Desktop：详情、书库、Updates 和 Downloads 页面使用同一 planner/result；反馈显示加入、跳过、失败数量及原因。
- Legacy：删除每个 ScreenModel 各自过滤 downloaded/queue items 的逻辑。
- Focused 验证：planner contract、双端 queue adapters、partial failure、DI、数据库/文件边界测试。
- 关闭条件：任一入口对同一 chapter snapshot 选择相同目标；文件路径和包格式仍完全平台化。
- 预计：4–6 工程日，约 7–13 个文件。

### `DL-02` 补齐 Desktop 下载队列并统一跨页 wiring

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`DL-01`、`LU-01`。
- RED：队列 observe、pause/resume all、取消 eligible item、retry error、delete downloaded、reorder（若原版/当前产品支持）、清空确认、source/manga grouping、退出重启恢复与失败反馈。
- GREEN：Downloads ScreenModel 只消费 shared actions 和 Desktop executor state；详情/书库/Updates 的动作立即反映到同一队列。
- UI：顶部管理动作始终可达；危险清空/删除确认；空队列、暂停、离线、路径不可写和部分失败有明确反馈。
- Desktop 增强：保留普通文件/CBZ、打开下载目录和宽屏队列；这些只扩展 adapter/effect。
- Legacy：删除跨页面独立 queue shadow state 和重复 retry/cancel eligibility。
- Focused 验证：mounted Downloads、cross-screen integration、runtime restart fixture、Test Mode download subset。
- 关闭条件：只有一个 production queue/action source；状态改变可由四个页面一致观察。
- 预计：3–5 工程日，约 6–11 个文件。

### `UP-01` 迁移 Updates 完整查询与动作

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`LU-01`、`DL-01`、`MD-05` 的 selection reducer。
- RED：日期分组、included/excluded category、downloaded filter、search/empty/error、single/range/select all/invert、read/unread、bookmark/unbookmark、download/delete/cancel，以及章节打开请求。
- GREEN：直接复用 `GetUpdates`、chapter/download use cases；从原版 Updates model 提取纯 grouping/filter/selection/action reducer，两端 production consumer 共享。
- Desktop UI：补齐原版批量动作和 selection feedback；删除下载等危险动作确认；refresh/update job 入口显示 shared update result。
- Reader 边界：章节点击只验证 `ReaderOpenRequest`，不验证 Reader 内部。
- Legacy：删除 Desktop `UpdatesScreenModel` 中独立 filter/batch/reader target 决策。
- Focused 验证：shared reducer、Android Updates wiring、Desktop mounted/DI/navigation、cross-download integration。
- 关闭条件：动作 inventory 全绿；更改 shared reducer 会同时使 Android/Desktop production tests 失败。
- 预计：4–7 工程日，约 8–15 个文件。

### `HI-01` 迁移 History 完整查询与动作

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-03`、`MD-04`。
- RED：按日期/最近阅读分组、search、单条删除、清空确认、恢复阅读请求、next chapter resolution、非书库作品加入/分类、duplicate 跳转和迁移入口（如 tracked upstream 保留）。
- GREEN：复用 history interactors、`GetDuplicateLibraryManga` 和 shared membership/reader target policies；提取原版 History state/action reducer。
- Desktop UI：保留现有搜索/删除/清空，补齐缺失菜单；空历史、源丢失、无下一章和数据库失败有明确反馈。
- Reader 边界：只验证恢复阅读请求与目标章节选择。
- Legacy：删除 Desktop `HistoryScreenModel` 内重复 next-chapter、favorite/category 和一次性 event 决策。
- Focused 验证：shared reducer、Android History wiring、Desktop mounted/navigation/DI、数据库集成。
- 关闭条件：两端对同一 history fixture 显示相同领域分组并产生相同动作结果。
- 预计：3–5 工程日，约 6–11 个文件。

### `ST-01` 复用完整统计聚合并补齐 Desktop UI

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`NR0-02`、`DL-01`；追踪统计直接消费现有共享 track repository/interactors，不依赖追踪调度器。
- RED：library total/completed/started/local/updateable、chapter total/read、sources、download count/size、read duration、trackers、mean score，以及空库、缺 tracker、损坏下载元数据和部分数据失败。
- GREEN：把原版 `StatsScreenModel` 聚合主体提取到 shared interactor，直接复用 `GetTotalReadDuration`、`GetTracks` 和 repositories；文件大小统计走 port。
- Android：当前 Stats 页面成为 shared aggregation consumer，UI 格式化保留平台化。
- Desktop UI：展示与原版等价的全部区块、loading/empty/error/retry；宽屏图表或额外指标可作为 Desktop decorator。
- Legacy：删除 Desktop 仅计算基础漫画/章节/来源指标的独立聚合。
- Focused 验证：shared aggregation fixture、双端 production wiring、Desktop mounted stats、下载大小 adapter。
- 关闭条件：同一数据库 fixture 的数值在两端一致；本地化和布局差异不改变计算。
- 预计：3–5 工程日，约 6–10 个文件。

### `MG-01` 提取最新上游 Migration 核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `N/A 待记录` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RS-02`、`DL-01`。
- 权威：tracked-upstream `MigrateMangaUseCase`；覆盖 target remote update、chapter read/bookmark/dateFetch、categories、tracks/enhanced trackers、remove downloads、custom cover、notes、chapter/viewer flags、replace/copy 和 cancellation。
- RED：每个 migration flag、missing source、remote failure、track migration failure、file failure、replace=false、部分步骤失败与 cancellation；事务边界和可恢复状态显式测试。
- GREEN：按原版步骤提取 migration plan/executor；source、download、cover、enhanced tracker 为 ports，数据库更新尽量由 shared transaction/use cases 承担。
- Android：当前迁移入口切到共享核心；不能保留 app 内第二个完整 use case。
- Legacy：记录 Desktop 旧 `MigrateMangaLogic`/ScreenModel 决策的删除截止 `MG-02`。
- Focused 验证：shared migration、data transaction、Android wiring、adapter failure fixtures。
- 关闭条件：迁移字段不因平台不同而缺失；Android 生产入口断开 shared executor 时测试失败。
- 预计：5–8 工程日，约 9–16 个文件/超过 400 行。

### `MG-02` 切换双端迁移入口并保留 Desktop 增强

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MG-01`、`BR-01` 的 target search contract。
- RED：详情、History、书库批量和 Browse Migration 入口均构造同一 migration request；Desktop UI 暴露 chapters/categories/tracks/remove downloads/custom cover/notes/flags、replace/copy 和确认摘要。
- GREEN：Desktop 事务、检查点和崩溃恢复作为 shared executor 外层 reliability adapter；不得改变迁移字段或吞掉 core failure。
- UI：搜索目标、预览选项、危险删除确认、逐项进度、成功/部分失败/取消与可重试反馈完整。
- Legacy：删除 Desktop 独立迁移复制算法；Android app use case 只保留兼容入口或移除。
- Focused 验证：navigation/Screen/DI、mounted migration、数据库+file+track integration、checkpoint restart、Test Mode migration subset。
- 关闭条件：同一 request 在两端得到同一数据结果；Desktop 增强只提升原子性/可恢复性。
- 预计：4–6 工程日，约 8–14 个文件。

### `TR-01` 提取追踪更新、刷新和延迟重试核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`NR0-02`；与 `MG-01` 可共享已冻结 track ports，但避免同时改同一 contract。
- 权威：原版 `TrackChapter`、refresh、enhanced tracker、delayed update queue、登录/缺失服务和错误处理；已有共享 track repository/interactors 优先 `SHARE-DIRECT`。
- RED：多 tracker、未登录、进度不前进、远端失败入队、最高进度合并、重试成功删除、取消不入队、provider capability 和 typed error。
- GREEN：提取 platform-neutral tracking coordinator/queue policy；Android service 与 Desktop provider/network/scheduler 作为 adapters。
- Desktop UI：Tracking 页继续提供搜索/绑定/状态/评分/进度操作；失败和延迟重试状态可见，不以成功 toast 掩盖排队。
- Legacy：删除两端独立 highest-progress/queue/error mapping 规则；认证网页仍为 platform adapter。
- Focused 验证：shared queue/coordinator、Android service wiring、Desktop provider integration、MockWebServer、DI。
- 关闭条件：两端对相同 tracker result 产生相同本地状态和重试决定。
- 预计：4–7 工程日，约 8–15 个文件。

### `TR-02` 统一手动已读与追踪联动

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MD-05`、`UP-01`、`TR-01`。
- RED：详情/Updates 单章与批量 mark-read 计算最大章节号、检测 tracker 落后、按 preference 自动更新或提示、拒绝倒退、部分 chapter mutation 失败时只追踪成功项。
- GREEN：从原版详情 `markChaptersRead/refreshTrackers` 提取共享 post-mutation policy，调用 `TR-01` coordinator；Android/Desktop 页面只呈现 effect。
- UI：自动更新、需要确认、排队重试、无 tracker 和失败均有明确反馈；取消提示不回滚已成功的章节数据库更新。
- Reader 边界：Reader 自动进度产生的 tracking event 不在本任务实现；只保证将来可复用同一 coordinator。
- Legacy：删除 Desktop “只改数据库不联动追踪”和 Android ScreenModel 内重复判断。
- Focused 验证：shared policy、详情/Updates 双端 production wiring、partial failure、mounted prompt。
- 关闭条件：手动已读在两端触发同一追踪策略，且不会重复提交。
- 预计：3–5 工程日，约 6–10 个文件。

### `BR-01` 收口 Sources、Browse 与 Global Search

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`RS-03`、`NR0-01`；执行前吸收当时已提交的 Global Search 迭代，不覆盖在途改动。
- RED：source list enable/pin/language、popular/latest/search、source filters/paging/retry、global multi-source partial results、source selection、recent history、local source entry/scan、add-to-library/materialize、duplicate/default category 和 detail navigation。
- GREEN：直接复用现有 source reducer/query/error contracts；从原版 Browse/Global Search 提取仍重复的 query coordinator、paging/result action policy，详情落库统一走 `RS-03`。
- Desktop UI：保留宽屏多列、键鼠、搜索历史/清除和恢复状态；单源失败不吞掉其他结果，显示 per-source retry/错误。
- Platform adapter：source ClassLoader、cookies、browser challenge 和外部网页登录不进入 shared query core。
- Legacy：删除 Desktop 搜索/浏览各自的 materializer、分页合并和 membership 判断。
- Focused 验证：shared query contract、Android/Desktop production wiring、MockWebServer 成功/空/403/429/500/畸形、多源 partial failure、navigation/DI/mounted UI。
- 关闭条件：所有详情入口使用同一 remote materialize；Desktop 增强不复制查询核心。
- 预计：4–7 工程日，约 8–16 个文件/可能超过 400 行。

### `EX-01` 收口扩展 catalog/update/install 与平台 adapters

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`BR-01` 的 source/query boundary。
- RED：catalog store CRUD、fetch/parse、installed/update/obsolete/trust state、download integrity、install/update/uninstall、partial failure、auth/challenge、restart/load diagnostics；真实扩展 fixture 必须经过 production loader。
- GREEN：catalog、version/trust/update decision 和 typed result 共享；Android APK PackageManager/installer 与 Desktop JAR ClassLoader/filesystem 作为明确 adapters。
- Desktop UI：扩展列表、更新、安装/卸载、失败详情、仓库管理和重启提示完整；JAR-first、APK→JAR legacy 兼容、FlareSolverr 保留并清晰标注。
- 安全：签名/哈希/仓库信任不因平台降级；token/cookie 不写入仓库或日志。
- Legacy：仅在真实扩展兼容 fixture 覆盖后缩减 Android shim/APK conversion；必要兼容面记录原因，不为“代码相似”强删。
- Focused 验证：shared catalog/decision、Android install coordinator、Desktop real JAR load、MockWebServer、DI、Test Mode extensions subset。
- 关闭条件：业务决策共享，包格式与运行时加载保持平台化；每个 fallback 都有诊断和截止/保留证据。
- 预计：4–7 工程日，约 8–15 个文件。

### `BK-01` 提取备份创建/恢复计划与冲突合并核心

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`MG-01`、`TR-01` 的稳定模型；可先冻结 backup contract。
- RED：所有 backup options、manga/chapters/categories/history/tracks/preferences/source settings/extension stores/custom metadata、duplicate merge、missing source、corrupt/truncated/version mismatch、partial restore、cancel、progress 和 rollback。
- GREEN：保留现有 protobuf 模型，提取 platform-neutral create plan、restore plan、per-entity merger 和 typed result；Android URI/job 与 Desktop file/scheduler 作为 adapters。
- 双端：当前 Android creator/restorer 与 Desktop creator/restorer 消费同一 plan/merger，不只做“字段能 round-trip”的格式测试。
- Desktop UI：创建/恢复选项、文件选择、预检摘要、危险覆盖确认、逐项进度、部分失败报告和可恢复建议完整；自动备份显示路径/保留策略/失败。
- Legacy：删除两套独立冲突合并和默认选项决策；序列化和平台文件 I/O 可以保留 adapter。
- Focused 验证：shared plan/merger、Android fixture→Desktop→Android round-trip、corrupt/old-version、DI、mounted UI、scheduler integration。
- 关闭条件：同一 backup fixture 在两端生成相同 restore decisions；格式兼容和行为兼容均有证据。
- 预计：6–10 工程日，约 12–22 个文件/超过 400 行；备份属于数据安全边界，需一次 migration/rollback 专项审查。

### `SE-01` 建立设置动作矩阵并补齐 Desktop 设置

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：Phase 1–5 的 preference APIs 稳定；Reader 设置条目只引用 Reader roadmap，不在此实现其效果。
- 交付：逐项列出 General/Appearance/Library/Reader/Downloads/Tracking/Browse/Backup/Security/Advanced/About 的原版 key、default、范围、依赖显隐、migration、平台分类和 Desktop 入口。
- RED：typed preference default/persistence/migration、invalid legacy value、dependent visibility、settings search、reset/confirm、restart-required effect；缺任一原版非 Reader 设置时 manifest coverage 失败。
- GREEN：共享 preference/service 定义直接复用；Android/Desktop UI 独立呈现但消费同一 key/default/rule。平台不适用项明示 unsupported 或省略并有 manifest 豁免，不能静默换语义。
- Desktop UI：保留宽屏、搜索、快捷键、目录选择和 Desktop-only Reader/extension/FlareSolverr 选项；重启、清理缓存、重置等操作有确认和反馈。
- Legacy：删除 duplicate preference key/default 和只存在内存的设置；迁移一次性且幂等。
- Focused 验证：preference contracts、migration、Android/Desktop DI、mounted settings/search/accessibility、restart fixture。
- 关闭条件：所有非 Reader 原版设置均为 shared/direct、platform adapter、Desktop product 或 explicit exempt，无未分类项。
- 预计：5–8 工程日，约 10–18 个文件；Reader 设置只验收保存/入口，不验收 Reader 行为。

### `PA-01` 复核非 Reader 平台工作流

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`BR-01`、`EX-01`、`BK-01`、`SE-01`。
- 范围：deep links、open web、share/copy/save、credentials/cookies、WebView/browser challenge、notifications、app update、crash/debug logs、licenses/privacy 和平台 unsupported 能力；不含 Reader 图像/输入/生命周期。
- RED：每个 shared effect 均经过真实 Android/Desktop adapter；成功、用户取消、无 handler/权限、网络错误、文件不可写、系统能力不可用和 fallback 反馈完整。
- GREEN：共享 intent/result/error，不共享 Android Context/WebView/notification channel 或 Desktop browser/AWT/file picker；复用现有已验证 adapters，不为统一文件形状重写。
- Desktop UI：能执行的能力提供入口和结果；不能可靠执行的能力显示诚实降级，不伪造成功。
- Legacy：移除 UI 直调系统 API 和重复错误映射；保留的 platform adapter 进入逐项 allowlist。
- Focused 验证：真实 production adapters、navigation/DI、MockWebServer、无 handler/权限/取消 fixtures、Windows/macOS 可用路径。
- 关闭条件：现有 manifest 平台能力条目被动作级重新验证；`PLATFORM-EXEMPT` 有用户可见边界和复查条件。
- 预计：4–7 工程日，约 7–14 个文件。

### `QG-01` 删除非 Reader legacy 并收紧守卫

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：除 `QG-02` 外全部任务。
- RED：列出仍可从 production 到达的 `SaveSourceMangaForDetails`、`LibraryUpdateChecker`、独立 remote/chapter synchronizer、独立 detail filter/selector、独立 migration/backup/update decision 及到期 migration bridge。
- GREEN：删除、内联为薄 adapter 或标记有证据的长期 platform adapter；清理 dead DI、preferences、strings、fixtures 和 feature flags。
- 守卫：shared core 禁平台类型，UI 禁 repository/manager 直连，adapter 禁业务 policy，Android/Desktop 必须有 shared compiled edge；允许名单逐项有原因。
- 用户行为：无新增入口；所有已补齐功能与 Desktop 产品增强保持不变。
- 验证：各阶段 focused suites、architecture/parity mutation、DI、Spotless、`git diff --check`。
- 关闭条件：非 Reader 每个核心决策只有一个 production 实现；任何保留双轨都有批准的 ADR/期限，不能以注释代替。
- 预计：2–4 工程日，约 8–18 个文件，主要为删除；超过 400 行时记录删除量而不保留死代码。

### `QG-02` 更新机器权威并完成最终发布验收

> 状态卡：`TODO` · 权威/范围 `[ ]` · RED/基线 `[ ]` · Shared `[ ]` · Android `[ ]` · Desktop/UI `[ ]` · Legacy `[ ]` · Review `[ ]` · Verify `[ ]` · Evidence `[ ]` · Commit `[ ]`
>
> 记录：阻塞 `—` · 审查 `—` · 验证 `—` · 产物 `—` · Commit `—`

- 依赖：`QG-01`。
- 交付：更新 `parity-manifest.json` 的动作、symbol、双端生产路径、tests、Desktop product 和 exemptions；从 manifest 生成/核对人类可读功能比较，不反向编辑第二份状态。
- 交付：更新架构文档，说明 shared core/ports、各平台 composition root、Reader 边界、Legacy 删除清单和上游继续同步方法。
- 基线：manifest/report drift、缺 commit/test、过期 symbol、Reader scope 污染、一个窄行为覆盖整页等情况必须失败。
- 审查：逐页进行一次独立功能审查；不重新打开已有证据充分且无上游变化的纯平台细节。
- 自动验证：按第 10 节只运行一次最终全量 Android/Desktop/domain/data/E2E/Spotless 矩阵；重型 Gradle 由 coordinator 串行管理。
- 运行时矩阵：书库、分类、详情全动作、远端刷新、更新、下载、Updates、History、Stats、Migration、Tracking、Browse/Search、Extensions、Backup/Restore、Settings 和平台 workflows；Reader 只验收打开请求能到达 Reader 入口。
- 网络矩阵：成功、空、403、429、500、畸形、partial multi-source、取消与重试必须经过 production client/DI/ProxySelector；不得用独立 JDK helper 冒充应用链路。
- Desktop：运行 Test Mode/smoke；Windows/macOS 均使用 `scripts/build-desktop.sh` 生成和验收正式产物。Windows 报告只引用构建日志 `Final unpacked EXE:` 的实际路径。
- Android：完成 debug/release 构建和关键非 Reader 运行验收；确认共享提取没有改变原版默认行为。
- 验证：manifest schema/authority/report generation、links、`git diff --check`、Spotless，以及上述最终矩阵。
- 关闭条件：维护者可以从任一用户动作追到上游权威、shared core、双端 consumer、UI 入口和验证证据；动作 manifest 无未分类项，最终矩阵通过或真实外部阻塞有明确记录。
- 提交边界：机器权威、架构/功能差异文档、最终验证证据和 checkoff 在同一最终收口提交中完成；不得在其后再创建仅用于 `close/advance/record evidence` 的状态提交。
- 预计：5–9 工程日，约 4–10 个文档/manifest/test 文件，另含运行、构建和产物验收时间。

## 9. 依赖顺序与可并行边界

```text
NR0-01 → NR0-02 → RS-01 → RS-02 → RS-03

RS-03 → MD-01 → MD-02 → MD-04
                  ├────→ MD-03 → LB-01 → LU-01
                  ├────→ MD-05
                  └────→ MD-06
MD-02 + MD-04 + RS-01 ────────────────→ DL-01 → DL-02
LU-01 + DL-01 + MD-05 ────────────────→ UP-01
MD-03 + MD-04 ─────────────────────────→ HI-01
DL-01 ─────────────────────────────────→ ST-01

RS-03 ─────────────────────────────────→ BR-01 → EX-01
RS-02 + DL-01 ─────────────────────────→ MG-01 → MG-02 ─┐
NR0-02 ─────────────────────────────────→ TR-01 → TR-02 ─┼→ MD-07
MG-01 + TR-01 ─────────────────────────→ BK-01           │
Phase 1–5 preference APIs ─────────────→ SE-01           │
BR-01 + EX-01 + BK-01 + SE-01 ────────→ PA-01           │
全部功能任务 ─────────────────────────────────────────────┴→ QG-01 → QG-02
```

- `RS-01/RS-02` 是多数任务的共同前置，必须由一个上下文簇串行完成，避免两个工作流同时修改 manga/chapter contracts。
- `RS-03` 完成后，详情、书库更新和 Browse 可以在接口冻结时并行；修改同一 `DesktopUiDependencies`、DI module 或 manifest 时由主协调者串行整合。
- `DL-01` contract 冻结后，`DL-02` 与 `UP-01` 可以并行；`UP-01` 不得复制未完成的 download eligibility。
- `MG-01` 与 `TR-01` 只有在 track ports 冻结后才能并行，避免共同改 Track 模型/DI。
- `MD-07` 是详情最终门禁，必须等待 Migration 与手动 Tracking 联动；此前可以发布独立完成的详情子能力，但不能宣称“详情全部完成”。
- `BK-01` 涉及数据安全和迁移语义，独立审查时暂停同一备份模型上的并行写入。
- `QG-01` 前允许的迁移桥必须在对应任务状态卡登记名称和删除期限；超过相邻两个任务仍存在即视为阻塞。

预计总量修正为约 115–180 工程日，其中 `NR0-01` 的人工语义审查为 15–25 日，而不是原先低估的 3–5 日。单人连续执行约 23–36 周；在 Phase 1 契约冻结后以最多两个低冲突工作流并行，约 15–24 周。该估算不含 Reader roadmap，也不含等待上游、真实扩展源、macOS/Windows 构建机或第三方 tracker 恢复的外部时间。

## 10. TDD、分层验证与发布门禁

### 10.1 每个行为批次的红绿重构

1. **RED**：先让真实缺口在共享契约或 production integration 中失败，并确认失败原因是目标行为缺失。
2. **GREEN**：以原版代码谱系做最小提取/接线；先让 Android 使用 shared core，再接 Desktop adapter/UI。
3. **REFACTOR**：删除重复逻辑、迁移桥和 dead wiring，再跑同一 focused tests。
4. **REVIEW**：一轮独立审查关注原版语义、Desktop 增强、平台边界、危险操作、partial failure 和 Reader scope。
5. **COMMIT**：相关测试、production、文档/checkoff 同一提交；提交后才把任务勾为 `[x]`。

### 10.2 测试类型

- shared use case/reducer/policy：common/JVM contract tests，使用固定原版 fixture；不能在测试中复制 production 算法。
- database/transaction：SQLDelight/data integration，覆盖 rollback、partial failure、并发和重启恢复。
- HTTP/source/tracker/extension：MockWebServer 经过 production client/adapter，最低覆盖成功、空/缺失、403、429、500、畸形响应和取消。
- Screen/Tab/navigation：构造器冒烟、Voyager 类型与层级、每个 push/replace/current 的真实对象类型。
- DI：注册相关完整 module，解析新类型；断开 binding 时测试必须失败。
- UI：mounted/离屏测试覆盖真实入口、loading/empty/error、确认、一次性反馈、键盘和鼠标等价操作。
- architecture：compiled edge、禁用依赖、mutation fixture；只作为 wiring/边界证据的补充。
- E2E/Test Mode：覆盖跨页面链路和真实持久化，不用源码扫描代替。

### 10.3 阶段门禁

| 门禁 | 最小验证 |
| --- | --- |
| Phase 0 | 12 类独立负例、20 样本 stop-gate、F1–F8 cluster mutations、authority/manifest/architecture、`git diff --check`、Spotless |
| Phase 1 | shared/domain/data、Android remote update callers、Desktop materialize、MockWebServer、DI |
| Phase 2 | Android manga focused、Desktop detail full suite、Screen/navigation/DI、Test Mode detail subset |
| Phase 3 | library/category/update/download shared tests、双端 wiring、Desktop scheduler/runtime、Test Mode subset |
| Phase 4 | Updates/History/Stats shared + 双端 production/mounted tests |
| Phase 5 | Migration/Tracking shared、database/file/network integration、双端 UI/wiring |
| Phase 6 | Browse/Extension/Backup/Settings/platform focused、真实扩展与跨端 backup fixture |
| Phase 7 | architecture/parity、最终全量、Test Mode/smoke、Android 与 Windows/macOS 正式构建运行 |

### 10.4 最终一次全量

Windows PowerShell 先设置 UTF-8 环境，并由 Gradle coordinator 串行运行重型验证：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python scripts/gradle-coordinator.py run --key non-reader-final -- ./gradlew :domain:jvmTest :data:jvmTest :app:testReleaseUnitTest :app-desktop:jvmTest :test-desktop:test spotlessCheck assembleDebug
```

随后运行 Desktop 运行时和正式构建验收：

```bash
./scripts/desktop-smoke-test.sh
./scripts/build-desktop.sh
```

阶段内只运行 focused/模块测试，不在每个任务后重复 `finalParityAudit`、完整 Desktop 测试和发布构建。最终 Windows/macOS 产物必须来自构建脚本，不能用 `app-desktop/tmp/`、Gradle `build/` 或辅助客户端替代。

## 11. 进度与证据记录模板

每个任务状态变化追加一行；任务处于 REVIEW 但尚未提交时仍保持 `[ ]`。内部 checkpoint 也使用同一表，以 `NR0-01.E` 等完整编号记录，`Commit` 在顶层原子提交前写 `N/A（随 NR0-01 提交）`；checkpoint `INVALIDATED` 时保留失败原因，不覆盖成新的假绿记录。

| 日期 | 任务 | 状态变化 | 权威/范围 | RED/基线证据 | Shared/双端/Legacy 结果 | 独立审查 | 验证/产物 | Commit |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| — | — | — | — | — | — | — | — | — |

每个任务提交前同时记录：

- 变更文件数/行数；超过提示值时说明内聚性和主要风险；
- 被删除或保留的 legacy symbol；保留项必须写平台理由和复查条件；
- Android 与 Desktop 真实生产调用路径；
- 用户入口、成功/失败反馈、危险确认和功能边界；
- focused/阶段测试命令与实际结果；
- 审查结论、修复复审结论（若有）和 commit hash。

## 12. 风险、回滚与完成定义

### 12.1 主要风险

| 风险 | 控制 |
| --- | --- |
| 再次把窄共享 helper 当成整项 parity | 动作级 inventory；每个动作绑定 production path 和 UI；整页最终门禁 |
| 机械 helper 演变成自动语义映射器 | helper 零语义输出；禁止 synonym/override/评分/自动豁免；20 样本前不得扩展 |
| 生成器与测试复制同一逻辑而互相证明 | 12 类手写破坏性负例；测试不导入或复制 helper 语义；digest 只做 drift guard |
| 审查发现系统性缺陷后继续打补丁 | 同类错误出现两次即 checkpoint INVALIDATED；回到最近 PASS，不新增例外继续扩张 |
| “共享”只是新 facade，旧 executor 仍运行 | Android/Desktop compiled edge + mutation；Legacy 字段和相邻任务删除期限 |
| 为 KMP 重写导致脱离原版谱系 | 固定/跟踪上游 symbol、fixture 和迁移 diff；优先移动/参数化原方法体 |
| 平台 adapter 偷偷持有业务规则 | port API 只传 typed intent/result；adapter allowlist；跨平台 contract test |
| 补原版功能时删除 Desktop 增强 | `DESKTOP-PRODUCT` inventory 与 mounted regression；增强叠加在共享基线后 |
| 上游继续变化导致刚迁移就落后 | 每阶段一次 tracked-upstream review；先更新 contract 再调整 shared core |
| Migration/Backup 数据损坏 | 数据专项审查、事务/rollback、跨端 fixture、正式恢复前预检与确认 |
| 全量验证成本失控 | 红绿只跑 focused；阶段跑模块；最终只跑一次完整矩阵 |
| Reader 与非 Reader 重复实现 | `READER-OWNED` manifest 标签；只共享 ReaderOpenRequest/setting/tracking port 边界 |

### 12.2 回滚原则

- 每个功能批次保持一个可回滚提交边界；不使用长期双轨 feature flag 作为常态。
- 不改变数据库/备份格式时，失败回滚 production wiring 到上一已验证 shared batch，并删除临时桥。
- 必须改变数据格式时，先单独记录 migration/rollback 设计与版本门禁；未通过前不得切生产入口。
- 文件、下载、封面和 backup 写入使用临时文件/原子替换或现有安全机制；回滚不得删除用户原始数据。
- 外部 source/tracker/扩展不可用时记录真实阻塞和 fixture 证据，不把第三方失败改写成产品成功。

### 12.3 Roadmap 完成定义

只有同时满足以下条件，本文才可标记 `DONE`：

1. 非 Reader 动作 inventory 无未分类项；每项都有原版权威/上游变化、当前 Android、Desktop、共享/adapter 裁决和证据。
2. 漫画详情页的刷新、收藏/分类、过滤/排序/显示、继续阅读请求、选择/批处理、下载、追踪、迁移、封面、元数据、笔记和平台动作全部具有 Desktop 入口、反馈与必要确认。
3. 远端作品更新、章节同步、详情决策、书库更新、下载选择、Updates、History、Stats、Migration、Tracking、Backup 等平台无关核心由 Android/Desktop 实际共享，或有不可共享的技术证据。
4. 被替代的 Desktop 简化重写与 Android 私有决策实现已删除或不可从 production 到达；所有长期 adapter 有明确平台原因。
5. Desktop 独有能力均保留并有回归证据；没有为了原版对齐删除或降级宽屏、键鼠、文件/CBZ、JAR-first、FlareSolverr、Test Mode 等能力。
6. Reader 专项没有被本文复制；所有非 Reader 入口只通过稳定请求/偏好/追踪端口与 Reader 连接。
7. `parity-manifest.json`、架构文档和人类可读比较一致，历史 summary 不再被当作当前完整性证明。
8. focused、阶段、最终全量、Test Mode、Windows/macOS Desktop 构建运行与 Android 构建验收通过；产物和 commit 均已记录。
