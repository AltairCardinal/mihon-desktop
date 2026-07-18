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

**状态：当前未解决；会直接污染尚未完成的 Task 6B。**

`docs/roadmap/source-extension-authority-baseline.md:4` 和相关 Task 1 报告把 `d77ef4d2b63e00d8abe3e2da85b6ef4e4351ae58` 作为 authority baseline；该提交是当前功能分支，不是固定 `main`。`docs/superpowers/plans/2026-07-15-mihon-source-extension-shared-core.md:630` 进一步要求以当前 `ExtensionsScreenModel`、`ExtensionManager`、`ExtensionDetailsScreenModel` 的“现有行为”为权威 fixture。

当前 `app/` 已经与 `main` 有明确差异：

- `ExtensionManager.kt:135-148` 在 `scope.launch` 中异步初始化；固定 `main` 同一流程为同步初始化。
- `ExtensionsScreenModel.kt:180-184` 收集安装流时没有固定 `main` 的 `takeWhile { step != Installed }`。

这些差异未必都应简单回退，但在完成来源分析之前，不能把它们当成原版事实供 Desktop 对齐。

**应纠正为：** Task 6B 开工前把 fixture 与调用链重建在固定 `main` 上；当前 Android 的差异逐项归为“迁移必要 adapter”“已验证 bugfix/增强”或“待偿还 fork 技术债”，不得整体继承为权威。

### C7. Extension trust / transaction shared core 是安全增强，却被整体纳入“原版对齐”叙事

**状态：当前分类混淆；安全能力本身应保留。**

当前 shared/Android 实现加入了仓库 fingerprint 连续性、声明/下载/已安装 SHA 校验、事务 snapshot、rollback 和 runtime restore。固定 `main` 有 Android 签名信任与安装器流程，但没有这套仓库身份连续性和跨平台事务协调器。当前 extension 相关代码相对固定 `main` 已达到 21 个文件、约 `+2085/-279` 行差异。

这些是有价值的安全与可靠性增强，不是“Desktop 独有简化应向原版回退”的对象；但把它们和真正从原版提取的 update policy、安装状态、取消语义放在同一个“Android 权威”标签下，会让后续审查无法判断哪些行为必须逐字对齐、哪些行为允许超集。

**应纠正为：** 分成两层：原版兼容核心（版本比较、签名/安装基本语义、状态与取消）和显式安全增强层（repo identity、SHA continuity、transaction rollback）。两端可以共同消费增强层，但验证报告必须写成“原版兼容 + 双端安全增强”，不能声称全部来自原版。

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
| 待施工 | Global Search 选择与执行策略 | 默认 `PinnedOnly`，可切换 `All`；同 query/filter 不重复请求，切换 filter 复用交集结果；最多 5 个 source 并发 | Desktop 仍缺 Pinned/All 策略、重复查询抑制、交集复用和并发上限；必须保留 generation/session retirement，不能用回退到简化协程替代 |
| 待施工 | Global Search 状态投影与排序 | 默认保留每个选中 source 的 Loading/Empty/Content/Error 行；非空优先、pinned 次之、最后按名称/语言；Has results 可切换并持久化 | Desktop 当前永久丢弃 Empty/零结果 Loading，并按结果数量降序；仍需补逐源反馈、原版排序、Pinned/All、Has results 与完成数/总数 |
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
