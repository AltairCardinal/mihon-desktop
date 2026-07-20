# 原版 Mihon / Desktop Android 构建版施工代码混淆审查

日期：2026-07-18

审查分支：`claude/pensive-vaughan`

审查基线：原版 Mihon 固定为本地 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`

施工范围：`1dd3e83efba17cea77280f9a5507cfffb9084600..70b0ef56c190d0945370b67451f6c7b13e18db46`

## 1. 结论

本次按《Mihon Desktop 上游对齐重构计划概念混淆清单》逐项复核施工代码，并扫描了重构开始至当前提交的全部生产代码变更。

- 发现 **7 组当前仍需纠正的权威来源或功能分类混淆**。其中 2 组已经形成可观察的实现差异，另外 5 组主要是把 fork 增强、迁移后 shared 代码或当前 `app/` 行为错误标为“原版权威”。
- 发现 **5 组施工中曾经发生、现已由审查修正的混淆**。这些不再作为当前代码缺陷，但必须保留记录，后续不能只凭现有测试全绿推断其最初来源正确。
- Source query 与 tracker 当前实现仍存在“基线取自功能分支、未固定到 `main`”的证据风险；本次静态对比没有确认新的当前行为偏差，故列为待补证据，不冒充已确认缺陷。
- 未发现 Desktop Android API shim 被当作原版实现，也未在状态、偏好、后台任务、书库链路中发现新的明确身份混淆。

这里的“组”按同一根因和修复动作聚合，不等于文件数或单条 bug 数。

## 2. 判定口径

本报告严格区分四种对象：

| 对象 | 本报告含义 | 能否充当原版权威 |
|---|---|---|
| 原版 Mihon | 固定 `main@6fbf6dfc` 的实现、测试和历史产物 | 可以 |
| 当前 Android 构建版 | 当前分支 `app/`，包含 Desktop fork 后的 KMP 迁移和新增行为 | 不可以；只能作为迁移后的 consumer 或待审对象 |
| Mihon Desktop | `app-desktop/` 的 JVM 产品实现 | 不可以 |
| shared core | `domain/common`、`data/common` 等迁移目标 | 不可以自证来源；必须能回链到固定 `main` 或显式声明为跨端增强 |

平台名称不能替代代码身份。路径位于 `app/`、测试名带 `Android`、或当前 Android 与 Desktop 都消费同一 shared 实现，都不能证明该实现来自原版 Mihon。

## 3. 覆盖范围与方法

重构首个相关提交为 `470cb44db2f4c2b090713a0cc6e3e7231346a064`，因此扫描从其父提交 `1dd3e83e` 开始。范围内共有：

- 284 个非 merge 提交；
- 470 个发生变化的文件，约 `+54,757 / -5,028` 行；
- 249 个去除测试后的生产 Kotlin、Gradle、SQLDelight、Proto 文件；
- 114 个修改生产代码的提交，其中 Desktop 62、Reader 21、Android 12、Extension 7、Source 4、Library 3、Backup 2、Migration 1、Login 1、其他 1。

检查方法：

1. 用固定 `main` 对照每项计划所称的“原版类”“Android 权威 fixture”和行为描述。
2. 用 `git log --follow` / 首次引入提交判断符号来自原版、fork 初期实现还是本轮迁移。
3. 检查 shared 实现是否由原版行为推导，还是由当前 Android/Desktop 实现反向提取。
4. 检查当前 Android 与 Desktop 的 production wiring，避免把测试 helper 或迁移后的 consumer 反称为原版权威。
5. 结合 `.superpowers/sdd/` 审查记录，区分当前未解决问题与施工中已修正问题。

## 4. 当前仍存在的混淆

### C1. Parity manifest 把“原版来源、shared 实现、当前 Android consumer”压成一个权威字段

**状态：当前未解决；证据系统根因。**

`app-desktop/src/test/resources/parity/parity-manifest.json` 只有 `authoritativeImplementation`，大量条目写成笼统的 `Android authoritative capability`；已施工的 Reader、Migration、Tracker 条目又直接把迁移后的 shared 类或当前 Android adapter 填进同一字段。例如 Reader 配对条目约在第 408 行把 `PageTransform.kt` 与当前 `PagePairingAlgorithm.kt` 共同描述为 Android 权威实现。

这会形成循环证据：当前 fork 行为 → shared 实现 → 当前 Android 委托 shared → manifest 宣称 shared/当前 Android 是原版权威。它也是下述 Reader、Extension、Migration 分类错误没有在门禁阶段被阻止的共同根因。

**应纠正为：** manifest 至少分列 `upstreamRefAndSymbols`、`sharedImplementation`、`androidConsumer`、`desktopConsumer`、`intentionalDeviation`；原版 ref 必须固定到 `main` 提交，shared 和当前 `app/` 不能回填到原版字段。

### C2. Reader 双页配对把 fork 新增算法误认成原版 Mihon 算法

**状态：当前未解决；已进入 shared 和当前 Android production wiring。**

以下类在固定 `main` 中不存在：

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagePairingAlgorithm.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PairingState.kt`
- `DualPagePairingTest`
- `DualPageViewerAdapterTest`

它们最早由 fork 早期提交 `bef51fc69` 引入，而不是来自原版 Mihon。施工 brief `.superpowers/sdd/task-4a-brief.md:10-18,29` 却把这些文件列为“Android 原版权威语义”和可直接复用对象。随后提交 `83c5a97f` 将其抽成 `domain/src/commonMain/kotlin/mihon/domain/reader/PageTransform.kt` 的 `ReaderPagePairing`，当前 `PagePairingAlgorithm.kt:6` 又称自己是 shared authoritative pairing algorithm 的 Android facade。

因此当前调用链实际上是：

`fork 的 Android 构建版算法 → shared → 当前 Android facade`，不是 `main 原版 → shared → 双端 consumer`。

**影响：** 默认配对向量被误标为原版语义；后续即使 Android/Desktop 契约测试全绿，也只能证明两端一致，不能证明与原版一致。

**应纠正为：** 保留有价值的双页能力，但把它分类为 fork/Desktop 产品增强；重新从固定 `main` 的 pager/webtoon 行为建立原版默认 fixture，再把配对增强作为显式 option 叠加，不能继续称当前默认向量为原版权威。

### C3. Android 备份 fixture 的生成提交不是 `main`

**状态：部分修正，当前来源标注仍错误；尚未发现 schema 字节布局差异。**

`data/src/commonTest/resources/backup/README.md:3-6` 和 `scripts/generate-android-backup-fixture.ps1:6` 把 `d376fa62fcbdc7f108251762f9645da0e23b89db` 称为 Android 备份模型来源。该提交属于当前功能分支，`main` 不是其祖先；它不能作为“原版 Mihon”身份凭据。

施工初版还曾用迁移后的 common schema/codec 生成所谓 authoritative Android fixture，再用同一 codec 解码，形成循环自证；该问题已由 `.superpowers/sdd/task-2a-review.md:13` 指出并改为从历史源码提取生成。当前生成器不再使用待验证的 current codec，这是已修正部分。

本次对比确认：`d376fa62` 与固定 `main` 的 `app/.../backup/models` 内容相同，因此目前没有发现 fixture schema 本身的字节布局偏差；但 `d376fa62` 相对 `main` 的 7 个备份生产文件仍有约 `+136/-139` 行差异，且 README 的“原版来源”结论在 provenance 上不成立。

**应纠正为：** 把生成器固定到 `main@6fbf6dfc` 或真实迁移前原版 release ref，并重新生成、比对 SHA；若输出相同，记录为“来源纠正、行为无变化”，不能仅修改文案掩盖来源。

### C4. Desktop 下载列表实际按漫画标题分组，却声称镜像原版按源分组

**状态：当前未解决；用户可见行为差异。**

`app-desktop/src/main/kotlin/mihon/desktop/ui/download/DownloadQueueScreen.kt:72-73` 写着：

> Group downloads by manga title (mirrors Android's by-source grouping)

实际代码却是 `queue.groupBy { it.mangaTitle }`。固定 `main` 的下载列表按 `source` 分组。这不是平台或技术栈必需差异；注释把不同规则说成同一规则，代码也保留了 fork 时为快速完成 UI 的简化。

该行最早来自 `bef51fc69`。Task 2B 后续修改过同一文件，但只修正了共享 worker 的调度维度，没有处理 UI 分组，因此“施工相关代码审查”和“全历史扫描”都命中此项。

**应纠正为：** Desktop UI 使用与固定 `main` 一致的 source 分组模型；若要额外提供按漫画查看，应作为用户可切换的 Desktop 增强，而不是用漫画标题替代原版默认。

### C5. 批量迁移的 checkpoint / WaitingForUser / retry 是跨端增强，不是原版编排抽取

**状态：当前分类混淆；增强本身不应删除。**

固定 `main` 的批量迁移主要是顺序遍历、记录失败并继续、响应取消；没有 durable checkpoint、`WaitingForUser` 事件和 Desktop 的持久重试队列。当前 `domain/src/commonMain/kotlin/mihon/domain/migration/MigrationOrchestrator.kt:82-114` 新增：

- `startIndex` 和 `Completed(nextIndex)` checkpoint；
- `WaitingForUser`；
- 逐项 `Failed` 事件。

当前 Android 又增加了固定 `main` 不存在的 `AndroidBatchMigrationRunner` 与 `MigrationFailureDialog`，Desktop 则消费持久队列和 retry。原路线和 manifest 把整个批量编排描述为“从 Android 原版抽取”，混淆了原版基础规则与为 Desktop/双端补齐的工程增强。

**应纠正为：** 将原版的遍历、失败继续、取消语义与新增的 durable/resume/user-decision 增量分开建模；新增能力保留并由双端共同使用，但标为跨平台可靠性增强，不得反写成原版已有行为。

### C6. Extension 施工基线取自功能分支，并把当前 Android fork 行为作为后续“原版 fixture”

**状态：已解决。Task 6B 的权威固定为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`，当前 `app/` 只作为被测 consumer。**

`docs/roadmap/source-extension-authority-baseline.md:4` 和相关 Task 1 报告把 `d77ef4d2b63e00d8abe3e2da85b6ef4e4351ae58` 作为 authority baseline；该提交是当前功能分支，不是固定 `main`。`docs/superpowers/plans/2026-07-15-mihon-source-extension-shared-core.md:630` 进一步要求以当前 `ExtensionsScreenModel`、`ExtensionManager`、`ExtensionDetailsScreenModel` 的“现有行为”为权威 fixture。

当前 `app/` 已经与 `main` 有明确差异：

- `ExtensionManager.kt:135-148` 在 `scope.launch` 中异步初始化；固定 `main` 同一流程为同步初始化。
- `ExtensionsScreenModel.kt:180-184` 收集安装流时没有固定 `main` 的 `takeWhile { step != Installed }`。

这些差异未必都应简单回退，但在完成来源分析之前，不能把它们当成原版事实供 Desktop 对齐。

**应纠正为：** Task 6B 开工前把 fixture 与调用链重建在固定 `main` 上；当前 Android 的差异逐项归为“迁移必要 adapter”“已验证 bugfix/增强”或“待偿还 fork 技术债”，不得整体继承为权威。

**闭合证据：** Task 6B1/6B2 的 fixture 与 expected value 均取自上述固定 main；`8dc608675` 恢复 `takeWhile { step != Installed }` 并让当前 Android 消费 shared reducer。异步初始化、事务 ID、active receiver 去重与 rollback 被明确分类为保留的工程/安全增强，没有反写成原版事实。

### C7. Extension trust / transaction shared core 是安全增强，却被整体纳入“原版对齐”叙事

**状态：已解决；安全能力作为显式超集保留。**

当前 shared/Android 实现加入了仓库 fingerprint 连续性、声明/下载/已安装 SHA 校验、事务 snapshot、rollback 和 runtime restore。固定 `main` 有 Android 签名信任与安装器流程，但没有这套仓库身份连续性和跨平台事务协调器。当前 extension 相关代码相对固定 `main` 已达到 21 个文件、约 `+2085/-279` 行差异。

这些是有价值的安全与可靠性增强，不是“Desktop 独有简化应向原版回退”的对象；但把它们和真正从原版提取的 update policy、安装状态、取消语义放在同一个“Android 权威”标签下，会让后续审查无法判断哪些行为必须逐字对齐、哪些行为允许超集。

**应纠正为：** 分成两层：原版兼容核心（版本比较、签名/安装基本语义、状态与取消）和显式安全增强层（repo identity、SHA continuity、transaction rollback）。两端可以共同消费增强层，但验证报告必须写成“原版兼容 + 双端安全增强”，不能声称全部来自原版。

**闭合证据：** Task 6B2b 只对齐 fixed-main 动作与副作用顺序，`ed577a8c2` 继续保留事务 ID、active receiver 去重、reload/rollback 等安全超集，并以失败原子性测试保护增强层；计划和完成证据均分别标注原版核心与安全增强。

## 5. 施工中曾混淆、现已修正的项目

### H1. Reader 共享契约初版遗漏或改写原版行为

`.superpowers/sdd/task-1-review.md` 曾确认以下偏差：

- 旋转后宽页拆分和 vertical/webtoon 方向映射不符合固定原版；
- tint、brightness、grayscale、invert 被合并为一个总开关，丢失原版独立开关语义；
- duplicate 标记保留旧选择，不等价于原版每次按当前章重新选择；
- 奇数像素拆分与原版历史 `splitInHalf` 不同，却一度未明确是有意修复。

这些问题在后续 Reader 修复中已处理。当前 `PageTransform.kt` 已明确把保留中线像素写成相对原版历史行为的 intentional deviation，因此该项不再算身份混淆；但 parity manifest 仍应将它放入 deviation 字段，不能和原版权威默认混写。

### H2. 下载公平调度初版按 `mangaId`，却声称匹配原版 Downloader

`.superpowers/sdd/task-2b-review.md` 确认初版 `DownloadQueueStateMachine` 按 `mangaId` 轮转，而固定原版按 `source` 分组调度。后续实现已改为 `sourceId`，重试退避也与原版 2/4/8 秒行为对齐。

当前 shared worker 规则已修正；但当前 Android Downloader 并未消费该 shared 状态机，所以“Android/Desktop 共用同一下载状态机”仍属于 wiring 证据过度表述。应描述为“Desktop 消费从原版复刻的共享策略”，除非以后确实接通 Android consumer。

### H3. 单部漫画迁移抽取初版会清除目标阅读进度并选错重复章节

`.superpowers/sdd/task-3b-final-review.md` 确认初版存在：

- 用非空 `read=false` 覆盖目标漫画已有的更高阅读进度；
- 用 `associateBy(chapterNumber)` 选择最后一个重复章节，而固定原版 `.find` 选择第一个；
- 在求最大已读章前后处理不可识别章节号的顺序与原版不同。

当前 `MigrationOrchestrator.kt:50-60` 已改为只对需要新增已读状态的目标写 `true`，并使用 `.find` 选择第一个匹配项。这组行为偏差已修正。

### H4. Tracker 初版是 Desktop 平行重写，不是原版抽取

`.superpowers/sdd/task-3b-review.md` 曾确认 Desktop 初版 generic tracker API 与原版 provider 行为不同，包括 AniList implicit flow 被改成 authorization-code、client secret 缺失、GraphQL 字段不完整、Kitsu bind/update 使用错误 ID，以及部分日期/初始状态丢失。

后续已删除平行 `DesktopTrackerApi`，加入共享 provider protocol，并补齐 Android production wiring 与 provider 集成测试；后续复审未发现上述偏差回归。当前 `TrackerProviderContracts.kt:3-6` 的“shared by Android's original trackers and Desktop adapters”结论方向正确，但仍缺固定 `main` 的逐 provider provenance 表，列入第 6 节补证据范围。

### H5. Backup fixture 初版循环自证

见 C3。初版用待验证的 common schema/codec 自己生成再自己读取，不能证明 Android 原版兼容；当前生成器已改为提取历史源码并直接用 ProtoBuf 生成，循环自证已消除。剩余问题只是历史 ref 仍选错为功能分支提交。

## 6. 基线受污染但本次未确认当前语义错误

### R1. Source query shared core

Source/Extension authority baseline 使用了 `d77ef4d2b` 而不是固定 `main`。不过在 Task 2 开始基线处，Source browse/global search 相关 Android ScreenModel 相对 `main` 的生产差异只有少量 preference accessor 语法变化，未发现已把 Desktop 规则反灌成原版规则的明确证据。

当前 `SourceMangaSearchService.kt:15-41` 将取消转换为 `Failure(Cancelled)` 等共享错误状态，这是新设计出来的跨端契约；应通过固定 `main` 调用链回放确认其 UI/协程语义，而不能只凭当前 Android/Desktop 都消费它就宣布原版一致。

### R1 更正（2026-07-18）：fixed-main 回放已确认 Source Browse / Global Search 差异

本段追加更正 R1 的早期结论。后续已直接读取固定权威 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的 `BrowseSourceScreenModel`、`SourceFilterDialog`、`SearchScreenModel`、`GlobalSearchScreenModel` 与 `AndroidSourceManager`，不再以当前 `app/` 或迁移后的 shared service 作为 expected value。回放确认以下差异：

| 状态 | 差异明细 | fixed-main 行为 | Desktop 处理 |
|---|---|---|---|
| 已修复 | Source filter 类型与交互 | source 创建的具体 `Filter` 子类和 `Select<V>` 数组原样回交 source；Reset 只重建 filter state，Apply 后分页沿用同一 listing | `5b2ad641c`、`b99ea3715`、`385ab7d1c`、`87adceae0` 已补完整 filter UI、draft 隔离、runtime subtype/array 保真、结构漂移校验与严格 production Compose 测试 |
| 已修复 | Source append 串行化 | Paging 顺序请求下一页，不允许重复或跳页；取消不会把旧页发布到新 listing | `e35078d5b`、`42bf801bd`、`dc1516b0b` 已加入不可变 in-flight key、owner/waiter 取消与异常清理、旧 generation 隔离；Desktop generation、typed error、exact retry 属于可保留可靠性增强 |
| 已修复 | source preference / manager 权责 | `AndroidSourceManager` 始终按 ID 解析已安装 source；`enabledLanguages`、`disabledSources` 只过滤发现与搜索候选 | `de03a8633` 对齐 `source_languages`、`hidden_catalogues`、`pinned_catalogues`、has-results app-state key；禁用 source 仍可解析，Browse 与 Global Search 通过显式候选策略过滤 |
| 已修复 | Global Search 选择与执行策略 | 默认 `PinnedOnly`，可切换 `All`；同 query/filter 不重复请求，切换 filter 复用交集结果；最多 5 个 source 并发 | `ad121fcbc`、`b416fd198`、`894b91400` 已补 Pinned/All 策略、交集复用、重复查询抑制与五源并发上限，并保留 generation/session retirement、typed error 与 recovery 增强 |
| 已修复 | Global Search 状态投影与排序 | 默认保留每个选中 source 的 Loading/Empty/Content/Error 行；非空优先、pinned 次之、最后按名称/语言；Has results 可切换并持久化 | `a492c9f4a` 已补完整逐源状态、完成数/总数、原版排序与真实偏好持久化；严格 Compose 场景重建验证过滤状态恢复，Task 3 的 Pinned/All 行为保持不变 |
| 待施工 | Global Search 结果生产链路 | 按 URL 去重，经 `NetworkToLocalManga` 复用本地记录；source 标题进入带当前 query 的单源浏览；不截断首屏结果 | Desktop 仍有 `.take(10)`、缺少 source 标题导航和完整本地记录观察；详情刷新与防重复打开属于可保留增强 |

这轮没有把 fixed-main 不存在的 Desktop typed error、登录恢复、generation 或 CAS publication 反写为“原版行为”；它们继续作为显式 Desktop/跨端可靠性增强维护。当前 `app/` 只用于验证迁移后的 Android consumer wiring，不参与生成上述 expected value。

### R2. Tracker provider contracts

Tracker 施工初版的明确偏差已修复，当前静态扫描未发现已知偏差回归。但 `TrackerProviderContracts`/`TrackerProviderProtocol` 是本轮新建的 shared 源，现有证据主要比较当前 Android consumer 和 Desktop adapter，未逐项固定到 `main` provider 实现。

**处理方式：** 在最终 parity verify 前补一份 `main ref + 原版 provider 方法/fixture + shared constructor + Android consumer + Desktop consumer` 映射，并重放 bind/update/auth/error 行为。补证据前状态应为“实现已共享、原版来源待核验”，不是“已证明原版权威”。

### R2 更正（2026-07-18）：fixed-main 回放已确认多项当前语义差异

本段是对上文 R2“未确认当前语义错误”的**追加更正**，不删除上文，以保留当时审查结论及其证据局限。经逐 provider 对照固定权威 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 后，R2 不再只是 provenance 待补证据：已经确认多项原版更优但尚未完整迁移的语义差异。

本轮重新固定四层身份：

- **原版权威：** 只包括 `main@6fbf6dfc` 中的 tracker provider、界面与测试；
- **当前 Android consumer：** 当前分支 `app/`，可能已经消费本轮 shared 迁移产物，不能反向证明原版行为；
- **shared migration output：** `domain/common` 等新建协议与构造器，只是迁移结果，必须逐项回链固定原版；
- **Desktop adapter：** `app-desktop/` 的认证、HTTP、凭据与 UI wiring，可以保留平台增强，但不得用增强掩盖缺失的原版业务语义。

#### 已确认差异与保留增强

| 判定 | 差异明细 | 固定原版行为与当前实现差异 | 后续处理 |
|---|---|---|---|
| 原版更优 | 非法状态不能静默回退 | 固定原版对 provider 不支持或无法映射的状态保留显式失败；当前 shared/Desktop 映射存在回退为通用默认状态的路径，会把数据错误伪装成成功。 | 以原版状态映射为共享核心；非法值必须返回可观察错误。 |
| 原版更优 | 五家生产配置不可用 | 固定原版可实际使用 MyAnimeList、AniList、Kitsu、Shikimori、Bangumi；Desktop production profile 当前把五者标为 unavailable，原因分别涉及 loopback redirect、implicit-token callback、client secret 或 provider 配置缺失。 | 逐家补齐可部署配置和真实登录链路；仅声明 profile 或测试构造器可用不算完成。 |
| 原版更优 | provider search model | 固定原版搜索保留 provider 所需的标识、媒体信息和后续绑定字段；当前通用 `TrackSearchResult`/解析路径会压平部分 provider 特有语义。 | 从固定原版 fixture 建立逐 provider 搜索契约，再让 shared model 表达其并集。 |
| 原版更优 | bind new / existing | 固定原版区分绑定远端已有条目与新建库条目，并保留 provider 的远端 ID/库 ID 前置条件；当前共享 bind 入口没有完整表达所有分支。 | 分开测试 existing-entry 与 new-entry，禁止用同一默认路径替代。 |
| 原版更优 | 阅读状态与日期 | 固定原版按 provider 规则同步 reading status、开始日期和完成日期；当前路径仍有默认状态或日期写入不完整的情况。 | 用固定原版状态/日期 fixture 做红绿测试，覆盖开始、继续、完成与回退。 |
| 原版更优 | refresh-before-update | 固定原版在更新前刷新远端绑定状态，避免用陈旧条目覆盖服务器数据；当前通用更新路径没有把该顺序作为强制契约。 | 在 shared 协议中显式建模 refresh → merge → update，并验证调用顺序。 |
| 原版更优 | MAL 专用错误 | 固定原版保留 MyAnimeList 的专用 API/认证错误语义；当前通用 HTTP/Tracker 错误会丢失 MAL 可操作反馈。 | 保留 provider-specific error，再在 UI 层映射为用户反馈。 |
| 原版更优 | private / date UI | 固定原版追踪界面允许查看或编辑隐私与日期字段；Desktop 当前通用 UI 未完整暴露这些能力。 | 在 Desktop 入口补齐字段、反馈和集成测试；平台布局可不同，能力不能缺失。 |
| 原版更优 | 远端删除 | 固定原版提供解除绑定时的远端删除语义；当前 Desktop 链路主要覆盖本地解绑。 | 明确区分 local unbind 与 remote delete，危险操作增加确认和结果反馈。 |
| 原版更优 | Enhanced auto-match | 固定原版 Enhanced tracker 会依据源/条目自动匹配；当前 Desktop 只验证配置可用性，未完整迁移自动匹配规则。 | 将原版匹配规则提取为可回放契约，Desktop adapter 只负责源会话。 |
| 原版更优 | Suwayomi delete flag | 固定原版 Suwayomi 更新/删除请求携带其专用 delete flag；当前通用删除协议未证明保留该请求语义。 | 增加真实 request-shape 测试，确保 flag 从 UI 操作传到 HTTP 请求。 |
| Desktop 更优 | OS credential storage | Desktop 使用操作系统凭据存储隔离 token/secret，安全边界优于把认证材料当普通偏好数据。 | 保留为 Desktop adapter 增强，不反写成原版已有能力。 |
| Desktop 更优 | HTTP `Retry-After` | Desktop HTTP 层能够读取服务端 `Retry-After` 并给出明确退避时间。 | 保留并下沉为可复用增强，但不得替代 provider 专用错误。 |
| Desktop 更优 | 持久事件 / checkpoint | Desktop 可持久化追踪同步事件与重试 checkpoint，崩溃后可恢复。 | 保留为可靠性超集，验证不会改变原版一次更新的业务结果。 |
| 需要合并优势 | 重试策略 | 固定原版含 provider/操作语境下的失败处理；Desktop 有 HTTP 退避与持久恢复，单独采用任一侧都会丢失另一侧优势。 | 以原版可重试边界和错误分类为核心，叠加 `Retry-After`、checkpoint 与幂等恢复。 |
| 待补证据 | Komga DNS | 当前证据不足以判断 Desktop 网络/DNS 处理是否与固定原版 Komga 语义等价或更优。 | 增加固定原版调用链、DNS 成功/失败和 Desktop adapter 集成回放。 |
| 待补证据 | Kitsu / MangaUpdates request shape | 现有测试证明当前构造器能发送请求，但尚未逐字段证明 URL、method、body、ID 与固定原版一致。 | 使用固定原版请求 fixture 做 MockWebServer 精确断言；补证前不判定优劣。 |

#### Task 3B 为何漏检

Task 3B 的实现与复审把当前分支 `app/` 的 provider 测试当成了 upstream evidence。由于当前 Android consumer 已经接入同一批 shared constructor/protocol，“Android 测试通过 + Desktop adapter 测试通过”只能证明两个 consumer 对迁移产物一致，不能证明迁移产物与固定原版一致。审查当时没有把 `main@6fbf6dfc` 的逐 provider 方法、请求 fixture、状态映射和 UI 能力作为独立预言机，因此只捕获了早期 generic API 的明显回归，漏掉了迁移后双方共同具有的缺失或改写。

#### 后续 TDD 分层与 ID 69 闭合条件

后续修复必须按以下层次执行红绿重构，且下层通过不能替代上层证据：

1. **固定原版 fixture 层：** 从 `main@6fbf6dfc` 提取 provider 搜索、bind、refresh/update、状态/日期、错误和删除的输入输出及 request shape；fixture 必须记录 ref、路径与符号。
2. **shared 契约层：** 先用固定原版 fixture 写失败测试，再修改 shared model/protocol；需要保留的 Desktop 增强以显式 extension/deviation 测试覆盖。
3. **当前 Android consumer 层：** 验证迁移后的 `app/` wiring 没有偏离固定原版；它是被测 consumer，不是 expected value 的来源。
4. **Desktop adapter 层：** 用真实 HTTP parser/client、OS credential adapter 和 provider 配置验证成功、空数据、认证失败、限流、畸形响应与 request shape。
5. **产品链路层：** 覆盖登录、搜索、绑定、刷新后更新、状态/日期编辑、远端删除、Enhanced auto-match、Suwayomi delete flag，以及失败时的用户可见反馈。

在上述已确认“原版更优”条目完成 TDD、五家 production profile 可真实使用、待补证据项完成判定，并且 parity manifest 的 ID 69 使用结构化 fixed-main provenance 区分原版、shared、当前 Android consumer、Desktop adapter 与 intentional enhancement 之前，**ID 69 不得标记为完全 parity 或完成闭合**。

## 7. 未发现概念混淆的已扫描区域

本次扫描未在以下区域发现“当前 Android 构建版被当作原版”或“Desktop Android shim 被当作原版”的明确证据：

- app state、DI、preferences、network、background task、notification；
- library、category、cover、chapter batch 等已施工链路；
- `app-desktop/src/main/kotlin/android/` 平台兼容 shim；
- shared update version policy；其版本号/libVersion 比较与固定原版一致。

这只表示没有命中本次“身份混淆”问题，不代表这些区域已经完成普通功能 parity、性能或集成验收。

## 8. 后续施工门禁

在继续 6B 及后续 Task 前，应按以下顺序处理：

1. 先修 C1：拆分 manifest 的原版来源、shared、双端 consumer 和 deviation 字段，否则后续 checkoff 仍会循环自证。
2. 将 C2、C5、C7 重分类为“原版兼容核心 + 显式增强”，保留功能，不做机械删除或回退。
3. 将 C3 fixture ref 改为固定 `main` 并重生成 SHA，确认输出是否变化。
4. 修复 C4 的 Desktop 下载默认分组，使用户可见默认行为与原版一致。
5. 6B 开始前先修 C6 authority baseline；禁止以当前 `app/` 的现有行为整体充当原版 fixture。
6. 对 R1/R2 补固定 `main` 的调用链和行为 replay；若 replay 发现偏差，再作为新的实现修复项进入 TDD。

任何“当前 Android 与 Desktop 测试结果相同”只能证明双端一致，不能替代“与固定原版 Mihon 一致”的证据。

## 9. 2026-07-18 追加审计：Global Search、Extension 与 Desktop Android shim

### C8. Global Search 权威清单遗漏结果生产、观察与导航链路

**状态：已解决。** `156bac203`、`94d3abbcd`、`81e302345`、`51f8314e8` 与 `20fd5dd41` 依次闭合 canonical contract、结果物化、可见卡观察及导航/完整结果行；最终 9 类 focused tests 107/107，独立审查通过。

当前 parity manifest、fixed-main path inventory 与 source/extension authority baseline 只记录了固定原版的 `GlobalSearchScreenModel`、`SearchScreenModel`，遗漏了 presentation `GlobalSearchScreen`、`GlobalSearchCardRow` 以及 `SManga.toDomainManga()`。这会让下列 Desktop 简化实现绕过 provenance 门禁：

- `GlobalSearchScreen.kt` 直接以 `SManga` 作为最终 UI 结果，并用 `.take(10)` 截断每个源的结果；
- 源标题没有进入携带当前 query 的单源浏览；
- 只在点击结果时调用 `SaveSourceMangaForDetails.awaitListedForDetails()`，而非在发布结果前完成 URL 去重和 `NetworkToLocalManga`；
- `SaveSourceMangaForDetails.awaitListed()` 手工只映射少数字段并强制 `initialized=false`，遗漏 artist、author、description、genre、status、updateStrategy 等原版转换语义；
- 卡片没有通过固定原版 `getManga(initialManga)` 对本地记录持续观察，收藏、本地标题或封面变化不能反馈到搜索行。

固定原版权威为 `SearchScreenModel.search()` 的 `distinctBy { it.url } -> NetworkToLocalManga`、`SearchScreenModel.getManga()` 的 URL/source 观察链、`GlobalSearchScreen` 的带 query 源标题导航，以及 presentation `GlobalSearchScreen` / `GlobalSearchCardRow` 的不截断展示。必须补入的 fixed-main 路径包括：

- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/GlobalSearchScreen.kt`
- `app/src/main/java/eu/kanade/presentation/browse/GlobalSearchScreen.kt`
- `app/src/main/java/eu/kanade/presentation/browse/components/GlobalSearchCardRow.kt`
- `domain/src/commonMain/kotlin/mihon/domain/manga/model/SManga.kt`

Desktop 的 generation/session retirement、CAS publication、typed error、精确 retry/login、防重复打开与详情后台刷新可以保留；它们不能替代搜索结果发布前的 canonical 本地记录建立。后续拆为 `global-search-canonical-result-wiring`（≤6 文件/300 行）和 `global-search-result-navigation`（≤4 文件/250 行）。

闭合后的 Desktop production 链路先对搜索结果按 URL 去重并通过 `NetworkToLocalManga` 建立 canonical 本地记录，再仅为进入 composition 的卡片按 `(sourceId, url)` 观察数据库；源标题携带当前编辑 query 进入单源浏览，目标页直接以 fresh filters 首载 Search，默认入口仍首载 Popular。每源结果不再 `.take(10)`，12 项 Compose 探针证明索引 11 可滚动、组合并订阅。Desktop 的防重复打开和原始 `SManga` 后台详情刷新作为不冲突的增强保留。

### C9. Task 6B 遗漏原版扩展分类器与 presentation 行为

**状态：已解决；缺失权威已补齐并由 shared contract 与当前 Android consumer 消费。**

Task 6B、authority baseline ID 37 与 manifest ID 37 只列出 `ExtensionsScreenModel`、`ExtensionDetailsScreenModel`、`ExtensionManager`，却遗漏真正定义 updates/installed/available/untrusted、NSFW、obsolete、语言拆分等规则的 fixed-main `GetExtensionsByType`，也遗漏 presentation 动作与安装阶段反馈。必须补充以下权威：

- `GetExtensionsByType`：`showNsfwSource`，installed 的 obsolete/name 排序与 `hasUpdate` 分区，available 排除已安装/不受信任项，多语言扩展按 enabled language 拆分；
- `ExtensionsScreenModel.searchQueryPredicate()`：逗号分隔子查询，匹配扩展名、source name、baseUrl 与数值 source ID；
- `ExtensionsScreen`：Pending/Downloading/Installing/Error/Idle 反馈与 install/update/cancel/trust 动作；
- `GetExtensionSources`、`ToggleSource`、`ToggleIncognito`、`ExtensionDetailsScreenModel`：已启用源优先、单个/全部启停与卸载后退出详情；
- `ExtensionDetailsScreen` 与 `ExtensionsTab` 的入口、反馈和导航语义。

当前 Desktop `ExtensionSearch.kt` 只处理单一 trimmed query，并以 package name/source name 搜索；`ExtensionLanguageFilter.kt`、`ExtensionListScreen.kt` 使用局部语言与默认 `showNsfw=false`；列表、安装任务和详情 enabled 状态仍在 Composable 内维护；`DesktopExtensionApi.installExtension()` 又把丰富安装状态压成 terminal `InstallResult`。这些均不等价于固定原版。package name 搜索、宽屏 Tab、语言多选、仓库 fingerprint/SHA/rollback/runtime restore、APK/JAR/ClassLoader、目录与仓库详情可作为 Desktop 增强保留，但必须叠加于原版核心语义。

建议拆分为：fixture/provenance 补全、分类搜索共享核心、动作生命周期共享核心，以及分别的 Desktop 列表 UI 与详情/设置 UI；每项继续遵守 8 文件/400 行上限。

**闭合证据：** Task 6B 已按 6B1a/6B1b/6B2a/6B2b/6B2c 拆分完成；`3dc50793a`、`eb37d645d`、`9401b363a`、`ed577a8c2`、`8dc608675` 分别闭合 fixed-main 分类/search、shared action、Manager side effect 与 Android UI wiring。Desktop 仍有的 presentation 债务明确留在 Task 6C/6D，不再冒充 Task 6B 或原版事实。

### C10. OpenSpec 2.1/2.3 的完成状态扩大了已共享事实

**状态：已解决；2.3 已拆成可独立验收的 consumer 子项，父项保持未完成。**

`openspec/changes/align-sources-extensions/tasks.md` 已勾选 2.1 和 2.3，但 `SourceMangaSearchService` 并不负责源列表，`domain/.../extension/presentation/` 尚不存在，Desktop `ExtensionListScreen` 与 `ExtensionDetailsScreen` 仍分别维护 catalog snapshot、过滤、install jobs、update-all、terminal error、source enabled、incognito、cookie 与卸载状态。因此“共享了部分 query/catalog/install 类型”不能写成“Android 与 Desktop production manager/ScreenModel 已消费相同 presentation state/error”。

2.1 应缩小为 shared query request/page/error core；2.3 应恢复未完成，或拆成 source query core、extension transaction core、当前 Android presentation consumer、Desktop presentation consumer。只有 fixed-main fixture、shared contract、当前 Android consumer 与 Desktop consumer 四层证据同时成立后才能重新勾选。

**闭合证据：** OpenSpec 2.3.1–2.3.4 分别记录 source query、extension transaction、当前 Android presentation 与 Desktop source result；2.3.5 Desktop extension presentation 仍未完成，因此 2.3 父项继续保持未勾选。

**2026-07-20 追加闭合（supersedes 上一段的实时状态，不改写历史审计）：** `e501c67a8a` 已完成 2.3.5 Desktop extension presentation production wiring，随后 OpenSpec 2.3 父项与 2.3.1–2.3.5 均保持已勾选。该 checkoff 只证明 production manager/ScreenModel 消费共同状态与错误；它不等于 3.4.3、4.4、4.5、4.6 或 Step 7/8 所要求的最新 Android/Windows/macOS 运行时验收、最终审查和 change 完成。

### C11. Desktop Android shim 尚未被直接冒充原版，但 Task 7 证据会循环自证

**状态：未发现代码直接把 shim 称为原版；现有证据不足以支持 Task 7 checkoff。**

当前兼容层至少有 40 个 Kotlin 文件、41 个顶层 public 类型；`compat-evidence.json` 只有 1 条且状态为 `unsupported`。`AndroidCompatTest` 只证明自建 stub 的局部行为；`ExtensionCompatibilityTest.MinimalTestSource` 的 JAR 只含 ServiceLoader 描述，Source 类来自测试 parent classpath，不能证明真实扩展 JAR 或 Android shim 被调用。唯一真实 ManHuaGui APK fixture 仍因缺少 `android.app.Application` binding 而明确 unsupported。

固定原版 `source-api` 的 `Source`、`CatalogueSource`、`ConfigurableSource`、`HttpSource` 定义扩展 ABI，`ExtensionLoader` 使用 Android PackageManager、签名与 APK runtime；固定原版不存在 Desktop 的 `android/**` 或 `AndroidCompat`。因此 shim 永远只能归入 Desktop adapter。真实 fixture 穿透 production loader 后实际调用的最小 adapter、APK→JAR、child-first ClassLoader、manifest discovery 与明确的不兼容诊断可以保留；合成自测只能作为 adapter 单测，不能升级为“真实扩展需要”的证据。

原 Task 7 的 7 文件/350 行不足以审计 40 个文件，应拆为 public surface inventory、真实 fixture evidence、按 package 分批的 compat prune、最终 parity evidence，以及独立的 Android/Windows/macOS 运行验收。Task 6E 的 Test Mode/导航暂未发现身份混淆，但它只能观察 production state，不得复制业务 reducer。

## 10. 2026-07-19 施工后复扫：仍待纠正的权威与行为混淆

复扫范围为重构起点父提交 `1dd3e83e` 至 Task 7C3a，并重点复核 `70b0ef56c..HEAD` 的 source、extension、shared、source-api 与 Desktop compatibility 施工。以下项目均有固定 `main@6fbf6dfc` 对照证据；已排除正在处理的 Comix ABI 修复和 Mangalix JsonReader/SystemClock 批次。

### C12. Parity manifest 仍有 47/64 项缺少固定原版来源

`app-desktop/src/test/resources/parity/parity-manifest.json` 只有 17 项包含 `upstreamRef`，其余 47 项仍可能把当前 `domain/common`、当前 `app/` consumer 与原版权威压在 `authoritativeImplementation` 同一字段中。IDs 28–40 已采用正确的 `upstreamRef`、`upstreamSymbols`、`sharedImplementationPaths`、`currentAndroidConsumerPaths`、`desktopConsumerAdapterPaths` 与 `deviations` 结构，其余条目应按每批最多 8 项修复；建议每批只改 manifest、fixed-main path inventory 与 `DesktopProductCapabilityContractTest`，约 200–350 行。

### C13. 主比较文档仍把当前 `app/` 称为原版，并把 fork 配对增强误写成原版

`docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md:4,74` 仍写“当前工作树中的 `app/`（原版 Android）”及“原版配对规则更成熟”；`docs/desktop-parity/PARITY_TRACKER.md:26` 仍使用未固定 ref 的“Android 权威行为”。固定 main 不存在 `PagePairingAlgorithm.kt`，该算法由 fork 提交 `bef51fc69` 引入。应以 fixed main 重新核验整份 95 项表，而不是只机械改两行；docs Task 预计 2 文件、200–250 行。

#### C13 后续施工清单：主比较文档逐行权威纠错（2026-07-18）

本清单只记录施工边界，不修改历史比较文档。唯一原版权威固定为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`；“当前 Android consumer”只表示 fork 当前消费端，不能反向证明原版行为。原表实际有 **96** 项，不是 C13 初审所写的 95 项。分类：`UPSTREAM_PARITY` 表示应以固定原版语义为准，`PLATFORM_ADAPTER` 表示只允许平台边界不同，`FORK_ENHANCEMENT` 表示当前 Android fork 新增、不可归因于原版，`DESKTOP_ENHANCEMENT` 表示 Desktop 产品增强且不得在对齐时删除。

路径缩写：`U:` 固定原版，`A:` 当前 Android consumer，`D:` Desktop。下列 48 项已完成证据复核；每批不超过 8 行，可独立施工和审查。

##### Batch C13-1：源发现、浏览与仓库（rows 28–32，5 项）

| 行 | 旧结论 | 固定原版路径/符号 | 当前 Android consumer | Desktop 实现 | 应改评分与差异明细 | 分类 |
|---:|---|---|---|---|---|---|
| 28 | 原版更优；管理维度/排序更成熟 | U:`GetEnabledSources.subscribe`、`AndroidSourceManager.sourcesMapFlow`、`SourcesScreenModel` | A:同名 consumer，另消费 fork 的 shared projection | D:`DesktopSourceManager`、`BrowseTab`、shared source projection | **不相上下**；Desktop 已恢复 reactive membership、last-used→pinned→language、筛选与 incognito gate；平台只保留源加载 adapter | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 29 | 原版更优；paging/错误恢复更成熟 | U:`BrowseSourceScreenModel`、`data/.../SourcePagingSource`、`NetworkToLocalManga` | A:`BrowseSourceScreenModel` 消费 source-api/shared mapper | D:`SourceBrowseScreen`、source query coordinator、`SaveSourceMangaForDetails` | **原版更优**；两端查询语义接近，但固定原版 Paging 生命周期、重试与长期扩展兼容仍更完整 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 30 | 原版更优；配置/异常处理更成熟 | U:`GlobalSearchScreenModel`、`SearchScreenModel`、`GlobalSearchCardRow` | A:同名 UI/ScreenModel，消费 shared source policy | D:`GlobalSearchScreen`、global query coordinator | **原版更优**；Desktop 已有并发、过滤、分组和重试，差距收敛为固定原版的成熟状态/错误编排 | UPSTREAM_PARITY |
| 31 | 不相上下；原版格式生态、Desktop 文件监听 | U:`source-local/.../LocalSource`、`LocalSourceFileSystem` | A:Android SAF adapter 消费 common local-source | D:`LocalSourceReader`、`LocalSourceScanService` | **不相上下**；原版的 SAF/格式/metadata 与 Desktop 的直接文件系统/监听是平台能力，不应互相复制 | PLATFORM_ADAPTER |
| 32 | 原版更优；Desktop 尚在补仓库兼容 | U:`ExtensionReposScreenModel`、extension repo domain | A:设置页 consumer 与当前 shared repo domain | D:`ExtensionRepoScreen`、shared repository/use cases | **原版更优**；核心仓库 CRUD 已共用，固定原版的 index 验证、更新联动与发布生态仍更成熟 | UPSTREAM_PARITY + PLATFORM_ADAPTER |

##### Batch C13-2：扩展生命周期、安全与登录（rows 33–40，8 项）

| 行 | 旧结论 | 固定原版路径/符号 | 当前 Android consumer | Desktop 实现 | 应改评分与差异明细 | 分类 |
|---:|---|---|---|---|---|---|
| 33 | 原版更优；APK 发布格式天然一致 | U:`ExtensionManager.findAvailableExtensions`、`ExtensionApi`、`ExtensionLoader` | A:同名 consumer + 当前 shared catalog | D:`DesktopExtensionManager`、`DesktopExtensionApi`、artifact scanner | **原版更优**；catalog 语义应上游对齐，APK/JAR 发现差异只能留在 adapter；Android 扩展生态仍是权威格式 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 34 | 原版更优；Desktop 转换/隔离较弱 | U:`ExtensionManager.install/update/cancel`、`ExtensionInstaller` | A:PackageInstaller/Shizuku consumer | D:`DesktopExtensionManager` install transaction、APK→JAR adapter | **原版更优**；Desktop 已有替换回滚/取消，但系统安装、签名与隔离只能由平台 adapter 实现 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 35 | 原版更优；Desktop stub/修补不保证全部 API | U:`ExtensionLoader` 的 PackageManager、签名、APK runtime | A:当前 `ExtensionLoader` 仍走 Android runtime | D:`DesktopExtensionLoader`、child-first loader、compat shim | **原版更优**；shim 是兼容 adapter，真实 APK fixture 证据不能改写成原版实现或完整 Android API 等价 | PLATFORM_ADAPTER |
| 36 | 原版更优；签名与平台隔离更强 | U:`ExtensionLoader.LoadResult.Untrusted`、`ExtensionManager.trust` | A:`ExtensionsScreenModel` 信任 consumer | D:artifact authenticity、trusted fingerprint、同 JVM loader | **原版更优**；信任状态机可对齐，进程/包隔离能力不可伪造，Desktop 需明确剩余风险 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 37 | 原版更优；Desktop 边缘状态不足 | U:`GetExtensionsByType`、`GetExtensionSources`、`ExtensionsScreenModel`、`ExtensionDetailsScreenModel` | A:同名 model/presentation | D:shared extension state、typed presentation port、详情/更新/卸载 | **不相上下**；Desktop 已接入 obsolete、NSFW、enable-all、model-routed actions；安全隔离差距归 row 36，不应重复降分 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 38 | 原版更优；复杂 Android preference 不兼容 | U:`SourcePreferencesScreen` | A:AndroidX Preference/Compose bridge | D:`SourcePreferencesScreen` + JVM preference compat | **原版更优**；常见设置语义可对齐，自定义 Android View/Preference 只能判为 adapter 不兼容 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 39 | 原版更优；内嵌 WebView 闭环更自然 | U:`WebViewScreenModel`、`WebViewScreen`、Android `CookieManager` | A:同名 WebView consumer | D:cookie import、source-login UI、外部浏览器/挑战 adapter | **原版更优**；Desktop 不得宣称拥有原版内嵌 WebView，只能保留清晰的外部登录与 cookie 同步降级 | PLATFORM_ADAPTER |
| 40 | 原版更优；Desktop 依赖手工 cookie/外部服务 | U:`CloudflareInterceptor`、WebView challenge flow | A:OkHttp + WebView cookie consumer | D:`DesktopCloudflareInterceptor`、`CloudflareChallengeManager`、FlareSolverr | **原版更优**；重试/cookie 语义可参考原版，挑战 UI 是不可共用的平台 adapter；外部自动化属于 Desktop 增强 | UPSTREAM_PARITY + PLATFORM_ADAPTER + DESKTOP_ENHANCEMENT |

##### Batch C13-3：阅读器模式、跨页与加载（rows 41–47，7 项）

| 行 | 旧结论 | 固定原版路径/符号 | 当前 Android consumer | Desktop 实现 | 应改评分与差异明细 | 分类 |
|---:|---|---|---|---|---|---|
| 41 | 不相上下；原版模式更多、Desktop 双页重要 | U:`ReadingMode`、`ReaderPreferences`、viewer factories | A:同名 consumer，另含 fork 双页 viewer | D:`ReaderModeState`、pager/webtoon/dual-page viewer | **不相上下**；固定原版提供 LTR/RTL/vertical/webtoon/continuous，Desktop 双页是大屏增强，不能归因给原版 | UPSTREAM_PARITY + DESKTOP_ENHANCEMENT |
| 42 | 不相上下；“原版已有配对算法且更成熟” | U:`PagerViewerAdapter.setChapters` 每源页一项；`PagerViewers` 仅方向差异；固定原版无 `PagePairingAlgorithm` | A:`PagePairingAlgorithm`、`PairingState`、`DualPageViewerAdapter` 是 fork 增强 | D:`DualPageState`、`DualPagePagerViewer`、edge matching | **Mihon Desktop 更优**；固定原版只拆单张宽页，不会配对相邻竖页；Desktop 有显式配对、封面/奇偶/edge matching，当前 Android 类不得写成原版证据 | FORK_ENHANCEMENT + DESKTOP_ENHANCEMENT |
| 43 | 原版更优；覆盖 webtoon/反转/旋转更多 | U:`PagerViewerAdapter.onPageSplit`、`PagerPageHolder.splitInHalf`、`WebtoonPageHolder`、`ImageUtil.splitInHalf` | A:固定逻辑 consumer + fork pairing | D:`VirtualPageList`、`PageSplitHalf` | **原版更优**；固定原版权威是单张宽图拆分，不含相邻页配对；Desktop 仍缺 webtoon/旋转组合的完整等价 | UPSTREAM_PARITY |
| 44 | 原版更优；区域解码大图更稳 | U:`ReaderPageImageView`/subsampling pipeline、`PagerPageHolder` | A:Android image holder/view | D:Compose zoom + Skia decode/crop | **原版更优**；手势可平台化，但区域解码与内存峰值属于尚未对齐的工程能力 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 45 | 原版更优；缓存/生命周期结合更深 | U:reader page loaders、`PagerPageHolder.process`、adapter lifecycle | A:当前 loader/holder consumer | D:`DesktopReaderPageLoader`、page preloader/cache | **原版更优**；Desktop 已有邻页预取和取消，仍需以固定原版的状态、失败重试和生命周期为语义基线 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 46 | 不相上下；Desktop 有自动滚动 | U:`WebtoonViewer`、`WebtoonAdapter.setChapters`、`WebtoonPageHolder.process` | A:同名 consumer | D:webtoon LazyColumn、auto-scroll/speed | **不相上下**；虚拟化/章节状态优先参考原版，自动滚动是 Desktop 独有增强不得因对齐删除 | UPSTREAM_PARITY + DESKTOP_ENHANCEMENT |
| 47 | 原版更优；章节边界反馈更完整 | U:`PagerViewerAdapter.setChapters`、reader transition/error models | A:当前 reader transition consumer | D:`ReaderScreenModel`、chapter transition/retry UI | **原版更优**；Desktop 已补章节切换、加载与重试，剩余差距是缺章/首尾过渡信息密度，不再描述为仅有底栏 | UPSTREAM_PARITY |

##### Batch C13-4：阅读交互、显示与进度（rows 48–55，8 项）

| 行 | 旧结论 | 固定原版路径/符号 | 当前 Android consumer | Desktop 实现 | 应改评分与差异明细 | 分类 |
|---:|---|---|---|---|---|---|
| 48 | 不相上下；各自适配主要输入 | U:`ViewerNavigation`、pager/webtoon input handlers | A:触摸/音量键 consumer | D:`ReaderKeyboardAction`、mouse/wheel/context menu | **不相上下**；只共享导航意图与方向语义，输入事件必须留在平台 adapter | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 49 | 原版更优；预设/反转组合更多 | U:`ViewerNavigation` presets、reader navigation preferences | A:当前 preset consumer | D:`ReaderNavigator`、tap zones/navigation modes | **原版更优**；Desktop 已消费主要预设，但完整反转矩阵与可验证的可视化映射仍少于固定原版 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 50 | 不相上下；Desktop 不需要移动端显示项 | U:`ReaderPreferences`、reader settings pages | A:方向锁/刘海/常亮等 Android consumer | D:`ReaderPreferences`、scale/background/crop/margin | **不相上下**；缩放与显示语义参考原版，方向锁/刘海/常亮和窗口设置分别是平台边界 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 51 | 原版更优；多灰度/反色/BlendMode | U:`ReaderPreferences`、reader color-filter presentation | A:Android color matrix/blend consumer | D:`ReaderColorFilter`、background theme | **原版更优**；Desktop 已有 RGBA、亮度、灰度与反色，仍缺固定原版完整 BlendMode/组合行为 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 52 | 不相上下；Desktop 保存自然、原版分享/封面完整 | U:`ReaderViewModel`/page actions 的 save-share-cover flow | A:Android permission/share/cover adapters | D:`PageSaveHelper`、context menu | **不相上下**；保存核心一致；系统分享、通知、封面和目录选择均按平台实现，不应强行共用 UI | PLATFORM_ADAPTER |
| 53 | 原版更优；“Desktop 尚无 tracker 联动” | U:`ReaderViewModel` progress、`TrackChapter.await` | A:当前 reader + tracking consumer | D:`ReaderProgressTracker`、tracker scheduler/session | **不相上下**；Desktop 已写 history/last-page/read 并联动 tracker；延迟/失败恢复的差距单列 row 70 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 54 | 原版更优；“Desktop 主要仅跳过已读” | U:`ReaderViewModel` chapter selection/skip predicates | A:当前 shared/fork skip consumer | D:`ReaderNavigator` 与 shared skip rules | **不相上下**；Desktop 已覆盖已读、过滤、重复章节三类规则，旧差异已失效 | UPSTREAM_PARITY |
| 55 | 不相上下；漫画级阅读模式对齐 | U:`ReaderViewModel`/manga `viewerFlags` | A:当前 manga settings consumer | D:`ReaderModeState`、manga-level persistence | **不相上下**；保持固定原版 viewerFlags 语义，Desktop 全局默认仅作缺省值 | UPSTREAM_PARITY |

##### Batch C13-5：下载、书库更新与预测（rows 56–63，8 项）

| 行 | 旧结论 | 固定原版路径/符号 | 当前 Android consumer | Desktop 实现 | 应改评分与差异明细 | 分类 |
|---:|---|---|---|---|---|---|
| 56 | 原版更优；Desktop 仅窗口内队列 | U:`DownloadManager`、`Downloader`、`DownloadStore`、`DownloadCache`、`DownloadJob` | A:同名 Android background consumer | D:`DesktopDownloadManager`、shared `DownloadQueueStateMachine`、persistent queue | **原版更优**；“仅窗口内”已失效，Desktop 已持久恢复并统一状态机；固定原版仍有更成熟的后台 Job/通知/缓存联动 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 57 | 原版更优；Desktop 只有 Semaphore | U:`Downloader` 按 source 分组、parallel source limit、retry | A:Android network/power constrained consumer | D:source-fair scheduler、retry/concurrency preferences | **原版更优**；“只有 Semaphore”已失效，Desktop 已对齐按源调度；OS 电量/网络约束与后台恢复仍是原版优势 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 58 | 不相上下；两端目录/CBZ | U:`DownloadProvider`、download archive pipeline | A:SAF/archive consumer | D:`DesktopDownloadProvider`、CBZ writer | **不相上下**；下载产物语义可共享，路径/SAF 与桌面文件可见性属于平台 adapter | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 59 | 原版更优；Desktop 规则较少 | U:`DownloadPreferences`、`FilterChaptersForDownload`、`LibraryUpdateJob` | A:Android WorkManager consumer | D:`DesktopDownloadPreferences`、shared filter、scheduler | **原版更优**；Desktop 已接入共享筛选和持久设置，差距集中于后台约束、触发可靠性和完整规则矩阵 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 60 | Mihon Desktop 更优；直接文件系统 | U:`DownloadProvider` SAF storage | A:Android storage adapter | D:`DesktopDownloadProvider`、open-directory flow | **Mihon Desktop 更优**；这是平台带来的可见优势，不要求模拟 SAF，但目录语义与命名仍应上游兼容 | PLATFORM_ADAPTER |
| 61 | 原版更优；后台可靠/系统约束 | U:`LibraryUpdateJob`、library update notifier/preferences | A:WorkManager consumer | D:`LibraryUpdateScheduler`、`LibraryUpdateChecker`、category filter | **原版更优**；核心检查/分类/反馈已存在，差距是应用退出后的调度与系统级约束，不应复制 Android WorkManager | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 62 | 原版更优；操作/后台联动更成熟 | U:`UpdatesScreenModel`、updates presentation | A:当前 updates consumer | D:`UpdatesScreenModel`、`UpdatesTab`、filter logic | **原版更优**；查看/过滤已接近，固定原版的多选、下载状态和后台联动仍更完整 | UPSTREAM_PARITY |
| 63 | Mihon Desktop 更优；原版无 upcoming | U:固定原版无独立 upcoming screen | A:`mihon/feature/upcoming/**` 是当前 fork 增强，不是原版 | D:`UpcomingScreen`、shared `GetUpcomingManga` | **Mihon Desktop 更优**（相对固定原版）；当前 Android 也消费该 fork 增强，但不得据此改写原版能力；预测边界保持明确 | FORK_ENHANCEMENT + DESKTOP_ENHANCEMENT |

##### Batch C13-6：历史、统计、迁移与追踪（rows 64–70，7 项）

| 行 | 旧结论 | 固定原版路径/符号 | 当前 Android consumer | Desktop 实现 | 应改评分与差异明细 | 分类 |
|---:|---|---|---|---|---|---|
| 64 | 原版更优；Desktop 管理动作不完整 | U:`HistoryScreenModel`、`HistoryScreen` | A:同名 history consumer | D:`HistoryScreenModel`、`HistoryTab` | **不相上下**；Desktop 已有搜索、继续阅读、单条删除、清空及确认，旧差异已失效 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 65 | 不相上下；隐身语义一致 | U:`GetIncognitoState`、`ToggleIncognito`、reader/source gates | A:当前 settings/reader/source consumers | D:general preference、reader/source-extension gates | **不相上下**；需持续用共享契约保证所有写历史/last-used/tracker 链路短路 | UPSTREAM_PARITY |
| 66 | 原版更优；统计维度更多 | U:`StatsScreenModel`、`StatsScreenContent`/`StatsData` | A:同名 consumer | D:`StatsScreenModel`、`StatsScreen`、shared aggregate | **原版更优**；Desktop 基础聚合已接线，来源/语言/状态等维度和可视化仍少于固定原版 | UPSTREAM_PARITY |
| 67 | 原版更优；原版选项/异常路径成熟 | U:`MigrateMangaUseCase`、`MigrateMangaDialog` | A:同名 use case/dialog consumer | D:`DesktopMigrateMangaUseCase`、`MigrationMangaScreen` | **不相上下**；Desktop 已重放分类、章节、历史、收藏/删除等固定语义，并以事务保留桌面可靠性增强 | UPSTREAM_PARITY + DESKTOP_ENHANCEMENT |
| 68 | 原版更优；Desktop 批量编排较轻 | U:`MigrationListScreenModel.migrateMangas`、`MigrationProgressDialog` | A:`AndroidBatchMigrationRunner`/shared durable runner 是 fork 增强 | D:`DesktopBatchMigrationController`、durable queue UI | **Mihon Desktop 更优**（相对固定原版）；Desktop 已有持久、可恢复批量队列；当前 Android runner 不能冒充固定原版证据 | FORK_ENHANCEMENT + DESKTOP_ENHANCEMENT |
| 69 | 原版更优；“Desktop 实际不可用” | U:`TrackerManager`、`TrackInfoDialog`、`TrackerSearch`、MAL/AniList/Kitsu/Shikimori/Bangumi/MangaUpdates/Komga/Kavita/Suwayomi providers | A:当前 tracking UI/provider consumers | D:tracker registry/services、`TrackingScreenModel`、认证与 scheduler | **原版更优**；旧“Desktop 无 UI/不可用”已失效，但 manifest ID 69 所列 production 配置、bind new/existing、refresh-before-update、状态/日期、provider-specific errors/search/private/date/delete/auto-match/Suwayomi 等债务仍未闭合 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 70 | 原版更优；“Desktop 无自动追踪” | U:`TrackChapter.await`、`DelayedTrackingStore`、`DelayedTrackingUpdateJob`、`ReaderViewModel.updateTrackChapterRead` | A:reader/tracking worker consumer | D:`ReaderProgressTracker`、`DesktopTrackerSyncScheduler` | **原版更优**；Desktop 已自动推送且有手动同步，旧“无”错误；固定原版的 delayed durable work、过滤/刷新和失败恢复仍更成熟 | UPSTREAM_PARITY + PLATFORM_ADAPTER |

##### Batch C13-7：备份、恢复与同步（rows 71–75，5 项）

| 行 | 旧结论 | 固定原版路径/符号 | 当前 Android consumer | Desktop 实现 | 应改评分与差异明细 | 分类 |
|---:|---|---|---|---|---|---|
| 71 | 原版更优；Desktop 字段更少 | U:`BackupCreator`、各 `*BackupCreator`、`Backup`/`BackupManga`/`BackupTracking`/`BackupPreference` | A:当前 app creator 消费 `data/commonMain` wire model | D:`DesktopBackupCreator`、共享 wire model typealiases | **不相上下**；Desktop 已使用共享 protobuf+gzip schema并覆盖漫画、章节、分类、历史、追踪、偏好、源与仓库，旧字段差异已失效 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 72 | 原版更优；恢复/错误汇总更成熟 | U:`BackupFileValidator`、`BackupRestoreJob`、`BackupRestorer` 及各 restorer | A:Android background restore consumer | D:`DesktopBackupRestorer`、`BackupWorkflow`、restore ScreenModel | **原版更优**；Desktop 已支持固定原版 fixture 与逐项/部分恢复，固定原版后台 Job、通知和错误汇总仍更成熟 | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 73 | 原版更优；退出后系统仍可调度 | U:`BackupCreateJob`、`BackupPreferences`、setup migration | A:WorkManager consumer | D:`AutoBackupScheduler`、retention cleanup | **原版更优**；内容格式已对齐，差距仅为应用退出后的系统调度/约束，不应把 WorkManager 搬到 Desktop | UPSTREAM_PARITY + PLATFORM_ADAPTER |
| 74 | 原版更优；“自有序列化、不能互通” | U:`Backup` serializers + protobuf/gzip decoder | A:当前 app 消费共享 `data/commonMain` schema/`BackupCodec` | D:`BackupCodec`、共享 model typealiases、fixed-main `android-full.tachibk` fixture | **不相上下**；已有双向 codec 和固定原版 fixture 证明 wire 互通；仍需按新增字段保持向前/向后兼容，不能只凭扩展名宣称兼容 | UPSTREAM_PARITY |
| 75 | 不相上下；两端均无通用云同步 | U:固定原版无通用 library cloud-sync | A:tracker/第三方能力只是局部同步 | D:同样仅 tracker/备份文件链路 | **不相上下**；不得把 tracker、备份文件或 fork 试验能力写成原版通用云同步 | PLATFORM_ADAPTER |

##### 尚未逐行复核的后续批次

- C13-8：rows 1–8；C13-9：rows 9–16；C13-10：rows 17–24；C13-11：rows 25–27。
- C13-12：rows 76–83；C13-13：rows 84–91；C13-14：rows 92–96。
- 每批仍须使用同一固定 ref，逐行给出 `U/A/D` 三层证据；不得以当前 `app/`、`shared/` 或 Desktop Android compatibility shim 补作固定原版权威。

### C14. Desktop 源列表仍是一次性快照，未响应扩展安装、卸载与 reload

`DesktopSourceManager.kt:28-32` 使用 `flowOf(getCatalogueSources())`，`BrowseTab.kt:100-135` 又以 `remember(sourceManager) { getCatalogueSources() }` 固定列表。固定 main 的 `AndroidSourceManager.kt:40-67` 订阅 `installedExtensionsFlow` 并重建 `sourcesMapFlow`，`SourcesScreenModel.kt:36-44` 持续收集；Desktop 已有 `DesktopExtensionManager.installedExtensions: StateFlow`，因此这不是平台限制。建议 Task `source-membership-reactivity`，desktop，5 文件/≤300 行；RED 必须证明不重建 Screen 也能观察安装/卸载后的列表变化。

### C15. Desktop 源列表遗漏 fixed-main last-used 投影与 incognito 边界

**状态：已闭合。** RED `87efbf3a9`、production/fixture `50d9bef77`/`7e77a9a1b`、本地化 repair `ef571737c` 与 incognito short-circuit repair `9acf0d8c4` 已恢复 fixed-main 的 last-used→pinned→language 投影及持久化边界。首轮 8 类 62/62、repair 54/54、最终 LastUsed+Projector 6/6；最终 review APPROVED、0 Critical/Important。Global login render/Channel 仅为矩阵稳定性 fixture follow-up，不计作业务闭合证据。

**审计时现象：** `BrowseTab.kt:114-135` 仍直接执行 pinned + alphabetic 简化规则，Desktop preferences 和浏览入口没有 `lastUsedSource`。固定 main 的 `GetEnabledSources.kt:18-40` 投影 last-used/pinned/language，`SourcesScreenModel.kt:47-67` 定义分组顺序，`BrowseSourceScreenModel.kt:97-99` 只在非 incognito 时记录 last-used。这是用户可见的发现/排序规则，不是平台差异。建议 Task `source-list-upstream-projection`，7–8 文件/≤400 行，不与 C14 合并。

### C16. Extension details 仍在 Composable 内维护源启停规则

`ExtensionDetailsScreen.kt:195-212` 直接遍历 `extension.sources`，每行用 `remember(source.id)` 保存 enabled；外部 preference 变化不会反馈，也没有 enable-all/disable-all。fixed main 的 `GetExtensionSources.kt:13-29` 订阅 `disabledSources.changes()`，`ExtensionDetailsScreenModel.kt:64-85,125-133` 定义 enabled-first、display-name 排序和单个/全部 toggle，presentation `ExtensionDetailsScreen.kt:113-120` 提供批量入口。Desktop 已有 shared `enabledFirst()`，未消费属于平行规则债。建议 Task `extension-details-source-state`，desktop，7 文件/≤380 行。

### C17. Extension details 丢失 obsolete 与 NSFW 用户反馈

**状态：已闭合。** RED `bd2a8862a`、GREEN `f609d182a` 与 wiring repair `f81566d05` 已在真实 zh-CN details projection 中呈现 obsolete、NSFW age-rating 与确认反馈；Metadata+Preferences 5/5、root Spotless 61/61，final review APPROVED、0 Critical/Important/Minor。

**审计时现象：** Desktop `ExtensionDetailsScreen.kt:146-269` 没有呈现 projection 中的 `isObsolete`，详情页也没有原版 NSFW age-rating warning；fixed-main presentation `ExtensionDetailsScreen.kt:168,173-176,191-215,457-470` 有警告与对话框。这些 Compose 反馈不存在平台障碍。建议 Task `extension-details-upstream-feedback`，desktop，4 文件/≤250 行，与 C16 分开。

### C18. Extension list 的 uninstall/reload 绕过 ScreenModel 并形成第二条业务路径

**状态：已闭合。** RED `c05f914e9` 与 GREEN `7c26a059e` 已将 reload/uninstall 的可见动作统一路由到 `ExtensionsScreenModel` / typed port，并保留 Desktop manager 的平台 side effect、事务 rollback、update-all、取消和 diagnostics。focused 1/1、整类 4/4、root Spotless 通过，review APPROVED、0 Critical/Important/Minor。

**审计时现象：** `ExtensionListScreen.kt:155-163,232-240` 直接调用 manager，卸载时忽略 Boolean 失败；但 `ExtensionsScreenModel.kt:155` 与 `DesktopExtensionPresentationPort.kt:98-99` 已有 typed uninstall。fixed main 的 `ExtensionsTab.kt:61-99` 统一经 ScreenModel。Desktop adapter 可以保留 reload 实现，但状态和错误反馈必须归 ScreenModel/port。建议 Task `extension-list-manager-bypass`，desktop，4 文件/≤250 行。

### C19. Source/extension 权威基线与当前真实施工状态漂移

`docs/roadmap/source-extension-authority-baseline.md:30,51` 仍写 6C/6D 待接入、ManHuaGui 因 Application binding unsupported；实际相关 Task 已完成，真实 loader 已越过 Application gap并将其解析为 required。`2026-07-15-mihon-source-extension-shared-core.md:66` 的“真实 Android/Desktop 权威类映射”也应改为 fixed-main authority 与双端 consumer/adapter 映射。建议 docs Task 3 文件/≤150 行，只追加 superseded/closure 状态，不篡改历史证据。

### C20. 活动 SDD 恢复入口仍指向保留的旧混淆路线图

`.superpowers/sdd/progress.md:4` 仍指向保留原文的 `2026-07-12-mihon-desktop-upstream-parity-roadmap.md`，没有引用已纠正的 `...roadmap-main-authority.md`。该 progress 文件未被 Git 跟踪，必须同时更新 live 指针，并在受版本控制的父/子计划元数据记录 corrected parent path；tooling/docs Task 1–2 文件/≤30 行。

### 本轮未发现新增混淆的区域

Task 7 compatibility 施工仍固定 `authorityRef = main@6fbf6dfc`，真实 APK 均有本地 SHA/provenance；Page ABI 以 fixed-main Uri descriptor 为兼容目标，View/WebView 仅完成 verifier 的类型仍保持 `unverified`，WebView fail-fast 也没有冒充 Android 浏览器支持。因此 Task 7C 当前施工没有新增“当前 app 或 Desktop Android shim 充当原版权威”的证据。

## 11. 2026-07-18 Task 7 最终门禁并行复扫

审查范围为 `852221f42..211b50ad3` 的 source/extension production、shared contract、当前 Android consumer、Desktop consumer/adapter、测试 fixture、manifest、OpenSpec 与维护文档。固定原版权威仍为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`；当前 `app/` 只作为 consumer，Desktop `android/**` 只作为平台 adapter。

本轮没有发现 Critical、Desktop 独有能力删除或 shim 冒充原版。下表保留审计时发现的 Important；行为修复证据已进入活动计划，其中 C15/C17/C18 的最终闭合证据同步列于本节之后。该行为闭合不替代尚未完成的最新 Windows/macOS/Android 运行验收。

| 审计项 | 当前偏差 | fixed-main 证据 | 整改 Task |
|---|---|---|---|
| C21 | 单源 Browse 发布 raw `SManga`，仅点击时持久化，数据库变化不回写卡片 | `data/.../SourcePagingSource.kt:42-59` 的去重、domain mapping、`NetworkToLocalManga`；`BrowseSourceScreenModel.kt:105-117` 的 DB 观察 | 7D11 `source-browse-canonical-result` |
| C22 | 空 append page 静默结束，丢失原版 retryable append error | `SourcePagingSource.kt:49-67` 对任意空页产生 `NoResultsException`/`LoadResult.Error` | 7D10 `source-empty-page-recovery` |
| C23 | Global Search 请求调度保留候选输入顺序 | fixed-main `SearchScreenModel.kt:86-94` 为 pinned-first，再按 name/language | 7D8 `global-search-source-order` |
| C14 复核 | Desktop 源成员仍为 `flowOf` + Compose `remember` 快照 | `AndroidSourceManager.kt:40-68` 与 `SourcesScreenModel.kt:36-44` 持续收集安装扩展变化 | 7D12 `source-membership-reactivity` |
| C15 复核 | 缺 last-used、incognito 写入边界及 last-used/pinned/language 投影 | `GetEnabledSources.kt:18-40`、`SourcesScreenModel.kt:47-80`、`BrowseSourceScreenModel.kt:97-99` | 7D13 `source-list-upstream-projection` |
| C16 复核 | Extension Details 在 Composable `remember` 中维护源启停，无外部观察与全部启停 | `GetExtensionSources.kt:13-29`、`ExtensionDetailsScreenModel.kt:64-85,125-133` | 7D14 `extension-details-source-state` |
| C17 复核 | Extension Details 未显示 obsolete/NSFW 反馈 | fixed-main presentation `ExtensionDetailsScreen.kt:168-215` | 7D15 `extension-details-upstream-feedback` |
| C18 复核 | Extension List 卸载/reload 绕过已有 ScreenModel/typed port | fixed-main `ExtensionsTab.kt:57-92` 所有动作经 ScreenModel | 7D16 `extension-list-action-routing` |

闭合更新：C15 由 `87efbf3a9`/`50d9bef77`/`7e77a9a1b`/`ef571737c`/`9acf0d8c4` 完成并通过最终 review（0 Critical/Important）；C17 由 `bd2a8862a`/`f609d182a`/`f81566d05` 完成并通过 final review（0 Critical/Important/Minor）；C18 由 `c05f914e9`/`7c26a059e` 完成并通过 review（0 Critical/Important/Minor）。C21/C22/C23/C14/C16 的逐项实现、测试和审查证据见活动计划 7D8–7D14；本次账本修正不重复提升其 parity 等级。

同时确认一个 Minor：shared 扩展排序的 `name.lowercase()` 与 fixed-main `String.CASE_INSENSITIVE_ORDER` 不是逐字同一 comparator；无参 `lowercase()` 本身是 locale-invariant，不能错误归因为 Turkish locale。7D9 必须先以 Unicode/mixed-case 对抗 fixture 证明真实差异；若不能取得 RED，则只保留 characterization，不得为对齐外观伪造 production 变更。

行为 Task 已通过后，OpenSpec 3.4 仍须保持父项未完成：IDs 29/37 只能记录到四层 production wiring 支持的 `WIRED`，不能在最新 Windows/macOS/Android 运行验收前提升为 `VERIFIED`。source-extension authority baseline 与本报告只追加 closure/superseded 状态，不篡改历史证据。历史比较文档的 superseded 提示和其余 “Android authority” 术语清理由 7D18 完成，避免再次把当前 Android consumer 当作 expected-value 来源。

## 12. 2026-07-20 最终审查 repair 状态（Task 7D23 记录点）

以下状态只说明独立代码审查结论，不提前宣布运行时或 change 完成：

- 7D19 Desktop artifact signer authenticity：`e455d55b0` 建立攻击 RED，`f8a054a04` 将 APK/JAR 实际签名绑定到 repository fingerprint；独立 review 为 APPROVED。
- 7D21 extension refresh failure presentation：`c4d7b27db` / `3be2de3b1` 闭合离线本地详情与失败反馈，`db8c902e8` / `c2d4517b3` 进一步串行化并发 refresh；独立 review 为 APPROVED。
- 7D22 当前 Android shared-query production wiring：`53affec75` 以可注入 production loader/service 的行为测试替代源码字符串扫描；独立 review 为 APPROVED。
- 7D20B Android initialization event handoff：`385b87283`、`8380f0807`、`51e74c60a` 暴露并修复 receiver/event handoff 后又取得 runtime reload RED；当前 repair 已实现但仍等待独立 repair review，因此不得记为 APPROVED。

本次 provenance reconciliation 只把 IDs 28、29、30、37 提升或保持到 `WIRED`，并补齐 fixed-main 路径/blob、当前 Android consumer、Desktop adapter 与保护测试映射。OpenSpec 3.4.3、4.4、4.5、4.6 以及计划 Step 7/8 继续未完成；任何 capability 均未因本文档更新提升为 `VERIFIED`。

## 13. 2026-07-18 原版身份混淆并行整改复核

本轮继续以 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 为唯一原版权威。两个子代理分别核验 Reader 与下载链路，并互相执行一次独立审查；当前 `app/` 仍只作为 current Android consumer，Desktop Android shim 未参与 expected value 生成。

### 已闭合或确认已由后续提交闭合

| 审计项 | 当前结论 | 闭合证据 |
|---|---|---|
| C2 Reader 双页配对身份混淆 | **已闭合。** fixed-main 默认恢复为一源页一显示单元，RTL 只反转；fork 相邻 portrait 配对改为显式 `pairAdjacentPortraitPages=true` 增强。当前 Android facade 与 Desktop 双页 adapter 显式启用，封面单页、forced single、edge matched pairs 和 landscape parity 均保留。 | `e9b34857e`；shared/current Android/Desktop focused GREEN；独立 repair review APPROVED，0 Critical/Important/Minor。 |
| C3 备份 fixture 来源 | **已由后续提交闭合。** generator 从 `android-full.original-mihon-ref` 读取固定 main，README 记录九个 fixed-main blob；重新生成与原 artifact 字节相同，SHA-256 仍为 `43FA65A3469932F4DA2794E8BDF69C7BEF7D65D4E77FE894E1B1798ED1EFAD8D`。 | `data/src/commonTest/resources/backup/README.md`、`scripts/generate-android-backup-fixture.ps1`。 |
| C4 下载默认分组 | **已由后续提交闭合。** 当前 Desktop 按 `sourceId` 分组并显示 source name，真实 Compose 测试会在退回 `mangaTitle` header 时失败。 | `483278b4d`、`275826c64`、`DownloadQueueSourceGroupingWiringTest`。 |
| C5 批量迁移增强分类 | **已由后续提交闭合。** manifest ID 68 已把 fixed-main 的顺序遍历/普通失败继续/取消，与 checkpoint、`WaitingForUser`、retry、Desktop 持久队列分别记录。 | `parity-manifest.json` ID 68；增强保留，没有反写为原版行为。 |
| C19 source/extension 基线漂移 | **已由后续提交闭合。** authority baseline 已固定 main，并追加 6C/6D、真实 ManHuaGui 与兼容证据的 superseded/closure 状态。 | `a2a4fd416` 与 `docs/roadmap/source-extension-authority-baseline.md`。 |
| C20 活动恢复指针 | **已由后续提交闭合。** live progress 已指向修正版主路线图，修正版父计划与 source/extension 子计划互相引用；保留的原路线图未改写。 | `a2a4fd416`、`.superpowers/sdd/progress.md`。 |

### 本轮新发现并已修复

#### C24. 下载排序与拖拽边界仍是 Desktop 简化规则

固定 main 只在每个 source header 内按 `dateUpload` 或 `chapterNumber` 排序，并禁止跨 source header 拖拽。Desktop 此前虽然已按 source 分组，但菜单仍全局按漫画标题排序，manager 也没有拒绝跨 source reorder。

`465a534ec` 已改为从 production `ChapterRepository` 读取 canonical 章节元数据，在每个 source 内提供上传日期最新/最旧与章节号升/降序；跨 source 拖拽为 no-op，同 source 即使在底层队列交错也只重排本 source 槽位。Desktop 原有的 `DOWNLOADING` 稳定优先、暂停/恢复、重试、清错和取消反馈全部保留。真实 Compose 测试点击菜单并观察 manager queue，helper/manager 测试另覆盖四方向、null-last、tie 稳定、活动下载和跨 source 边界。独立 repair review APPROVED，0 Critical/Important/Minor。

#### C25. Desktop fresh 阅读方向与显式双页入口未遵循原版默认

固定 main 的 fresh reader 默认 `RIGHT_TO_LEFT`；Desktop 此前默认 LTR。初轮 C2 修复又暴露 `DesktopReaderScreen(isDualPage=true)` 没有进入 production model，若只把全局双页默认改为 false 会让该显式入口失效。

`fbe35cbe3` 已将 fresh 默认改为 RTL，保留 current/legacy/per-manga LTR；shared navigation 不再提供容易被误作原版事实的隐式 LTR default，consumer 必须显式传方向。双页解析顺序固定为 per-manga flags > nullable screen override > global preference，screen override 经真实 runtime factory 到达 `ReaderScreenModel`；legacy dual-page true 继续迁移并持久化。独立 repair review APPROVED，0 Critical/Important/Minor。

### 红绿与验证证据

- shared pairing RED：恢复旧自动配对默认后，`ReaderParityContractTest` 38 项中固定-main LTR/RTL 两项精确失败；GREEN 为 40/40。
- Desktop defaults/override RED：恢复 fresh LTR、screen 默认 true 并忽略 override 后，204 项中 7 项精确失败；GREEN 已恢复。
- download RED：测试先于 production helper 落盘，`compileTestKotlinJvm` 因 `DownloadQueueOrder`/`applyDownloadQueueOrder` 四处未解析而失败。
- 最终 focused GREEN：domain 40、current Android reader 29、Desktop reader/download 231，共 300 tests，0 failures/0 errors；root `spotlessCheck` 通过。

### 仍待分批处理

- C1/C12：64 项 parity manifest 仍有 46 项缺少固定 `upstreamRef`/symbols；必须按每批最多 8 项补 fixed-main path/blob 与四层 consumer/adapter 映射，不能用 shared/current `app/` 循环自证。
- C13：历史主比较表已有 superseded 警示，但 96 项正文仍保留“当前 `app/` = 原版”的旧口径；修正版路线图存在不等于该比较结果已经重新核验，仍需按固定 main 重建可执行比较表。
- 下载拖拽的 Compose gesture harness 尚未直接模拟跨 header 手势；manager production boundary 已有行为测试，但 UI gesture 集成证据应在不复制 reorder 逻辑的独立 Task 中补齐。
