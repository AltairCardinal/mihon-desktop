# 双页配对修复预研：执行证据与放行结论

日期：2026-10-12。对应[完整设计](../2026-10-12-reader-pairing-determinism-design.md)。

**当前结论：继续优化中的隔离方案；局部修正有通过证据，尚未完成产品耗时验收，不能宣称完整修复。** 用户保留“读序无关”和“立即显示、后台不搬图”，并明确“立即显示”按实际体验和耗时对照验收。首轮将它写成绝对零分析等待过严；以下首轮反例保留，但不再用它断言所有方案不可行。继续研究见文末。

## 范围及基线

- 主工作区：`D:/Shell/Github/mihon`，基线 `fe7b2b6fb5`；开始时只有用户的 `docs/human/` 未跟踪目录，未修改／暂存它。
- 原型工作区：`D:/Codex/worktrees/reader-pairing-research-20261012`；分支 `codex/reader-pairing-research-20261012`，同一基线。
- 本轮最多两名子代理：一名承担隔离原型和 focused TDD，一名独立审查可行性／核心接口；主代理负责设计、证据核对与收口。不增加独立 roadmap 或逐任务报告。
- 没有改动主干产品行为，没有构建／安装 APK，没有操作连接的用户设备，没有执行全量或完整模块测试。
- 原型提交：`5b0514a57e450f1cd0ca6cf55f780aa5547f7fc8`。这是带有已知失败门禁的实验提交，不是完成修复的提交；未合并主干。原型 5 个文件，571 行新增／603 行删除，主要为同一 presentation owner 的内聚替换；大差异反映历史分组与显示生命周期的耦合，已独立审查，但不因此降低风险或分拆成虚假可交付功能。

## 现场证据复核

原始日志仍在主工作区忽略目录内，未将用户现场日志或漫画原图提交到 Git。

| 证据 | 位置 | SHA-256 |
|---|---|---|
| 卷 8 126/176 现场 | `.gradle-coordinator/reader-device-black126-20261012-035241/reader-diagnostics/reader-0.log` | `90d70c5e705d704f8bc659390a64ca53a47735ff0731a29b06ca134b697a4f1d` |
| 卷 9 局部曝光现场 | `.gradle-coordinator/reader-vol09-92-93-20261012-114131/reader-diagnostics/reader-0.log` | `a4b4eac6da614d1b0d3919f4a48db9130c1b8671dd87aafacff3e53ad91de3b3` |

既有诊断的通过表示“成功复现旧 bug”，不是修复通过。本轮分别建立期望修复行为的断言。卷 9 的编号不确定性和最初转圈报告的因果边界详见设计第 2 节。

## 红测证据

隔离工作区命令：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
python scripts/gradle-coordinator.py run --key canonical-red-tests -- gradlew.bat :domain:jvmTest --tests mihon.domain.reader.SharedDualPageCanonicalResearchTest --offline --console=plain
```

2026-10-12 08:41:17～08:42:13 UTC：4 个测试，4 个失败，构建退出码 1。失败均为真实 `SharedDualPageSession` 行为断言，非编译／依赖问题：

1. 相同事实从不同 initial target 进入，完整分区不同。
2. 卷 9 风格的邻屏单侧曝光后释放，再进入时伙伴消失。
3. 已显示旧组后晚到 LEFT，125/126 无法恢复配对。
4. 邻屏先曝光后补充页侧证据，113～116 仍被历史锁错。

第 4 条初始红测使用加强的合成侧向证据，只能证明该类型缺陷；真实 113～116 的识别结果中 114、115、116 均 UNKNOWN，必须另行验证，不混为同一个原图回归。

协调器日志：原型工作区 `.gradle-coordinator/canonical-red-tests.log`；SHA-256 `a7cfab6eb720ffb2a8557262082f43006c7cf5351c2b02a0282685a95f3b12a9`。此前 `canonical-red` 启动记录不作为上述行为红测证据。

## 原型与回归结果

隔离原型将章节分区改为内容事实驱动，将帧／绘制历史留作显示能力。它没有实现跨重开的事实索引，也没有完成双端独立的呈现投影。以下结果按运行时的版本分别保存，不把后续局部通过说成重新通过了整次审计。

| 执行阶段 | 范围／结果 | 证据（原型工作区内） |
|---|---|---|
| 基线红 | 初始研究 4 项，4 失败 | `research-evidence/canonical-red-tests.xml` |
| 首轮绿 | 研究 6 项，6 通过；含真实提示值的 113～116 和事实到达排列 | `research-evidence/canonical-green-tests.xml` |
| 相关契约审计 | 5 个指定测试类，98 项；65 通过，33 失败，0 跳过 | `research-evidence/contract-audit/`；协调器 `canonical-contract-audit` |
| 审查后追加红 | 研究 9 项，3 失败：封面槽位、连续竞争边的原子预留、反复进出元数据增长 | `research-evidence/canonical-regression-red.xml` |
| 追加修正绿 | 同一研究类 9 项通过；Android 原生 holder 集成 1 项通过 | `research-evidence/canonical-final-green.xml`、`canonical-native-green.xml`；协调器 `canonical-native-green` |
| 清理后最终验收 | 研究 9 项通过，严格门禁 2 项失败；执行 11 项，0 跳过 | 协调器 `canonical-final-gates`；XML 位于 `research-evidence/final-gates/` |
| 格式 | `:domain:spotlessKotlinCheck` 与 `:app:spotlessKotlinCheck` 通过 | 同一 `canonical-final-gates.log`，整体退出码 1 来自上述行为失败 |
| 原图事实补验 | 126 夹具补齐真实 127 RIGHT，相关用例 1 项通过，domain 格式检查通过 | 协调器 `canonical-original-126-check`，09:03:49～09:04:01 UTC |

表内 `research-evidence` 位于 `.gradle-coordinator/` 下。相关契约审计执行于 08:55:10～08:55:28 UTC，生产差异为 `SharedDualPageRules` 与 `ReaderDualPagePresentationOwner` 的中间原型；随后仅补封面半槽、一次让位后原子采用相邻对、历史元数据有界保留。首次审计 XML 始终保留，未重跑或声称最终原型通过全部 98 项。

最后一次补验仅更新 126 的测试夹具，没有再改 production；复用未受影响的 9 项／Android 原生／严格门禁结果。Android 通过发生在纯格式清理前，清理没有行为变化，不描述为格式化后重新跑过 Android。所有协调任务已进入终态，原型工作区干净。

审计范围与准确计数：

| 测试类 | 总数 | 失败 |
|---|---:|---:|
| `SharedDualPageCanonicalResearchTest` | 6 | 0 |
| `SharedDualPageRulesContractTest` | 14 | 0 |
| `SharedDualPageSessionContractTest` | 31 | 10 |
| `SharedDualPageStablePresentationContractTest` | 46 | 22 |
| `SharedDualPageStrongRequirementsResearchTest` | 1 | 1 |

这 33 项不能统一归为“旧测试过时”。其中既有旧策略明确依赖进入页／历史的断言，也有真实的呈现、迟到手动布局、票据和进度风险，以及本轮新增的严格条件失败。没有批量删除、禁用或降低旧测试来制造通过；继续研究按已确认的风险选择相关失败项修复、补验，不重跑整批来制造“全绿”。

### 已运行的严格条件反例

`SharedDualPageStrongRequirementsResearchTest.cold ready page and warm complete content must have identical first physical slot` 调用真实共享 Session，两个会话使用同一内容身份、同一目标页。热会话已知道前页宽图；冷会话只有目标图，稍后才得到相同宽图事实。

以下前置断言通过：两会话均允许已就绪目标获得绘制能力并报告绘制；冷会话补齐事实后旧帧没有移动。最后“相同内容首次槽位相同”断言失败：**热会话 RIGHT，冷会话 LEFT**。这证明“先猜半槽、之后冻结”的实验策略不够；“所有入口第一瞬间的物理槽位必须相同且绝不能等待任何分析”比用户随后确认的耗时标准更严格，不能把该测试解释为所有优化方向都不可行。这是共享状态机的实际执行反例，不是 Android 真机画面测试。

清理后 09:02:27～09:02:53 UTC 的 `canonical-final-gates` 保留该失败，另有 `facts arriving after real drawing must not lose the pending reading settlement` 失败：真实 Session 收到有效绘制回执之后、settle 之前，晚到宽图使旧帧 stale，`settle` 返回 null。它把审查发现的进度风险变成了可执行的否决证据。没有把失败反向改写为期望成功。

### Android 原生路径的窄验证

08:57:57～08:59:48 UTC，协调器 `canonical-native-green` 执行共享研究类和以下单个 Android 用例，退出码 0：

```text
:domain:jvmTest --tests mihon.domain.reader.SharedDualPageCanonicalResearchTest
:app:testDebugUnitTest --tests eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageViewportZoomTest.partialNeighbourNativePaintReleaseKeepsItsUndrawnPartnerOnEntry
```

环境为 Robolectric SDK 35、`GraphicsMode.NATIVE`。测试生成普通纵图，调用真实 holder 的图片链和 native Canvas；断言邻屏产生实际像素、只记录一页绘制，经过 detach／release，再经 viewer 移动和真实 adapter 重建，伙伴仍在目标双页内。`fixture.load` 的 CENTER 参数实际解析 production `pageSides()`，不是强行把普通双页放到中间。测试没有通过手工调用 `reportDrawn` 代替原生绘制。

它覆盖第 9 卷所涉及的单侧曝光／释放生命周期和 adapter 接线；不等于真实触屏滑动取消、全部手势、ART 或当前用户设备通过，也不验证 126 黑屏的整条原生路径。Android production 本轮未改，行为红证据来自修改前共享 Session 的失败回归，原生用例作为接线补验。Desktop 本轮未执行。

格式检查曾有被仓库 hook 跳过的相对路径调用，也有定位到真实文件后的待格式化结果；这些不记为通过。完成格式化后，最终检查任务实际执行或以相同输入的有效 UP-TO-DATE 结果通过。

## 独立可行性审查

审查发现需区分两个问题：

1. **历史污染可以独立消除。** 用唯一章节计划决定配对，实际绘制只管理当次帧／进度，能够针对永久单页、旧组占用建立真实回归。
2. **提前索引尚不能证明所有首屏都有足够信息。** source-api `Page` 不提供可靠完整版式事实；现有 Android 内容标识只在会话内有效；正常侧向采样目前发生在原生绘制阶段，不能仅把接受回调移到 `onImageLoaded` 就称提前识别。

冷跳反例和同内容 cold／warm 的推导见设计第 4 节。对竞争边的纯纵图情形也须覆盖：A/B 的未知边缘可能比 B/C 强或弱，而 B 本图完全相同。仅预读尺寸、仅保持旧帧，或只证明相同 facts 的结果一致，均不足以放行用户完整要求。

评审提出并已写入设计的边界包括：保留会话临时手动调整；canonical plan 与原生呈现投影分开；计划改变时不能混用旧帧与新计划的导航边界；持久事实绑定编码内容和分析输入版本。原型中实际未实现的部分继续标为缺口。

另外，首轮源码审查确认当时原型存在以下实质限制（继续研究的修复状态见文末）：

- `units()` 输出新计划，但正在屏上的旧帧仍可能保留旧布局；原生 adapter 更新可能重挂载，不能只看旧 ticket 尚存就认定像素稳定。按新计划 `navigate()` 也不能证明与实际旧帧边界一致。
- 当前旧帧被标记 stale 后，真实已绘制但尚未停稳的进度会被拒收；最终严格门禁已实际复现，不能声称已有阅读进度完全保持。
- 纯扫描本身线性，不代表 production 全链线性：真实 `ReaderSpreadSamplesOwner.distance()` 会线性查找 `identity.content`，整章逐边调用最坏为 O(N²)。测试的 O(1) lambda 不能证明 production 性能。
- 尚未实测双端持久索引、原图提取成本和真实设备首帧／翻页性能。旧下载已有文件也不等于已经具备稳定索引。

## 发布门禁与后续边界

本轮产物是设计、可复查的实验代码和测试证据。它不修改当前设备上的诊断 APK，不证明 Android ART／原生手势／厂商系统性能已经通过。

- [ ] 在冷旧下载／冷跳场景，按用户确认的实际体验和耗时对照，同时证明顺序一致、无可感知新增等待、已露出画面不移动。
- [ ] 实际 Android 与 Desktop 呈现投影、票据、进度和手动布局回归全部通过。
- [ ] 索引无效、内容替换、重试、缓存淘汰以及快翻超过预取速度时仍满足同一契约。
- [ ] 对目标 Android 路径完成可复现的首图／翻页延迟和资源对照。

这些门禁未通过前，不承诺完整修复，不把原型 cherry-pick 到主干，不要求用户用放宽条件来换取“完成”。

首轮结果只否决当时那版候选，不是停止研究的理由。继续研究保留用户全部要求，修正过严的验收解释，并解决事实准备、当前帧／导航／进度及性能问题。

## 继续预研：实际入口、成本与局部闭包

继续使用同一隔离 worktree、同一实施代理与独立审查代理；主工作区期间已有其他作者功能提交，未把这些并行改动带进实验基线，也未回滚它们。主代理仅维护本报告与设计，不合入原型产品代码。

### 实际本地链路与原图成本对照

重新读取实际实现后确认：`AndroidReaderChapterContentPort` 会在返回清单前逐页发布 Ready 本地页的 header；`ChapterLoader` 对 PREFETCH 显式跳过该步。因而“所有已下载场景都有未知前页宽高”和“所有已下载场景进入前均已齐备”都不成立，必须统一直接打开／预取切入／复用章的事实准备语义。header 本身不包含页侧结果。

外部 probe 调用真实 content port 与现有分类器，使用现场原始 WebP：卷 8 阅读器 111～128、卷 9 阅读器 91～95，共 23 张、3,849,732 字节。原图留在本地忽略目录。运行 `canonical-r2-preanalysis-probe`，2026-10-12 10:43:28～10:43:57 UTC，2/2 通过；证据 `.gradle-coordinator/research-evidence/r2-preanalysis-probe.xml`。

计时环境：Windows、Robolectric SDK 35、native Skia。先预热两轮，再交错执行七轮 baseline／candidate，每组 161 次；输入字节事先读入内存，**不包含文件 I/O、SSIV JNI、ART 或阅读器首屏／翻页时间**。candidate 复用同一次解码的 bitmap，调用 production `ReaderPageSideHints.classify` 以及 `SharedSpreadMatching.plan`，每页 388 个取样点；全部页侧值与原图诊断一致。

| 操作 | 中位数 | p95 | 最大值 |
|---|---:|---:|---:|
| native BitmapFactory 解码 | 9.0376 ms | 11.7539 ms | 12.9416 ms |
| 同一次解码后提取页侧／边缘 | 9.2247 ms | 11.5684 ms | 12.3842 ms |
| 单独的 production 样本提取阶段 | 0.1029 ms | 0.1686 ms | 0.2493 ms |

每组 161 次总计：decode-only 1427.4863 ms，decode+facts 1444.0576 ms，提取阶段 18.5546 ms。不同组 p95 的小幅倒置属于测量分布差异，不能宣称候选一定更快。真实 content port 的单次 23 页 header 路径为 31.5811 ms、23 次打开／关闭；禁用 header 路径为 0.1009 ms、零次打开。它证明所调用入口的差异，不是 176 页整章启动基准，也不替代 `ChapterLoader` PREFETCH→ACTIVE 的集成验收。

该结果支持“复用等价正常解码输入上的轻量取样”，不支持“每次先额外解码整章”或“安卓无感延迟已通过”。是否能直接复用显示下采样 bitmap，还须独立核验输入一致性；原生解码输出的提前回调尚未接入完整产品链。

### 新修正的验收边界

继续预研修复的重点是有限呈现投影、真实已绘制进度和真实边查询成本，而不是放宽配对一致性：已显示页保持原槽位，未锁定的宽图伙伴可独立成为下一屏；分组和源页导航不接收永久历史占用；settle 只能消费真实 draw 回执，不能自己创造“画过”的事实。边查询的内容索引维护须覆盖 rebind、内容变化和方向变化。

局部事实闭包需要完整状态摘要，不能只看一条不能配对的边：旧原型在禁止边仍向右发 reservation，已加入真实规则红绿；修正后仍要保留 opening 状态、左邻宽图状态、全章原始 ordinal 和手动优先级。真实 126 场景的 123～127 已知输入有望使不同前缀入态收敛到 125/126，不必一律追溯到 117；这必须由 production 规则执行证据支持，不能硬编码“五页总是足够”。

### 本批提交与有效验证组合

隔离原型提交 `4d65d3d67bd98cf8916edcd597976927972f7e0e`，父提交 `5b0514a57e450f1cd0ca6cf55f780aa5547f7fc8`。11 个文件，410 行新增／35 行删除，6 个 production 文件和 5 个测试文件，属于同一配对计划／呈现／绘制进度的内聚修正；未包含原图、ignored 探针或用户目录。它仍是实验提交，未合入主干。

| 证据批次 | 实际结果与范围 |
|---|---|
| `canonical-r2-lease-red` / `canonical-r2-wide-red` | 先复现新计划导致显示投影变化、进度丢失和未准备宽图伙伴不可达 |
| `canonical-r2-native-unwired-red` | 故意不接 Android 呈现投影时，真实 adapter 将已画 holder 判为需要重建，正确失败；随后恢复实际接线 |
| `canonical-r2-native-lookup-red` | 真实 samples owner 的 64 页／1000 次查询发生 127000 次身份列表读取，证明原生产路径不是常量查询 |
| `canonical-r2-boundary-cache-red` | 禁止边泄漏 reservation、无变化时呈现列表无谓重建的真实回归失败 |
| `canonical-r2-final-focused` | domain 31/31、Android native 2/2、domain/app 格式通过；为后续首绘补修之前的基线 |
| `canonical-r2-first-draw-red` 与后续绿循环 | 已准备未绘制页遇迟到事实，其首次真实绘制被误拒；放开后又由既有防伪用例抓出 settle 自己写 drawn 的漏洞。最终将 drawn 事件与 hasDrawn 查询分离 |
| `canonical-r2-settlement-native-green` | 本批最终 production 的原生 holder 两项通过；共享专项仅余一条旧“跳页改变组 anchor”断言，随后修正为精确 location 不变、canonical 组不变 |
| `canonical-r2-final-probes` | domain 执行 10 项，9 项本次受影响行为通过，1 项保留的旧 cold 首槽反例失败；domain 格式通过。它不是整体 BUILD 通过 |

最后一批只修改旧测试的分组 anchor 预期，仍断言用户指定的末页 location 不变，并增加“prepared-only 不能结算、真实 reportDrawn 后才可结算”。没有放宽进度安全条件。Android 两项通过证据来自同一最终 production 版本的前一批；没有描述成所有最终测试再次全跑。

Android 原生两项分别覆盖：单侧邻屏真实画像素、离开释放再进入伙伴仍在；晚到宽图改变逻辑计划时，现有 holder、native view、bitmap 及真实像素 bounds 保持，未准备伙伴以 FULL 成为下一页。共享专项还覆盖双活跃帧、分别释放后恢复 canonical、来源顺序覆盖、不越过宽图、真实绘制后迟到事实仍可结算、伪造伙伴／旧导航／释放票据拒收。

内容查询索引测试对 64／256 页执行各 1000 次真实 `distance` 查询，建表后身份列表读取为 0，且验证内容更新和 rebind 不沿用旧评分。这只证明该热查询消除线性搜索；`request/accept` 的其他列表扫描、每条事实后强制消费整个计划的累计成本尚未整体优化，不声称全链已经线性。

独立审查复核实际 diff、红测和分项 XML，关闭本批首绘／防伪进度阻塞。已记录一个尚未复现的可选风险：Android adapter 用同一布尔值合并多个章节 owner 的 release 刷新，首 owner 在调度前移除时可能漏另一个 owner 的一次刷新；不把这个推断写成已证实现场原因。全部协调任务终态，无残留验证进程；未执行全量、完整模块或旧 98 项重跑。

### 窗口证据与被否决的输入优化

同一 `canonical-r2-final-probes` 的外部 Android 探针执行两个独立方法；XML 记录 4 次执行、3 次失败，因为下采样断言自动重试了三次，不能称为四种独立故障。

1. **窗口不受前缀变化影响：通过。** 对 `[113,114]`、`[115,116]`、`[125,126]`、`[93,94]` 四个目标，将各窗口左侧四页枚举为普通 UNKNOWN／LEFT／RIGHT／宽图，再枚举 16 种前缀边评分，共 16384 次调用真实 `canonicalPlan`。窗口内采用实际页侧和已分析为 null 的竞争边，四组均不变。它为这些现场输入存在局部闭包提供执行证据；没有实现通用闭包认证器，也不代表任意远处、任意手动布局和任意章节已经穷尽。
2. **显示下采样不改变事实：失败，优化假设被否决。** 对全部 23 张原始 WebP 使用 `inSampleSize=1/2/4`，再调用同一 production 分类器。卷 9 阅读器第 95 页原图为 UNKNOWN，sample 4 得到 245×348 bitmap 后变成 RIGHT。固定输入原则因此具体化：不能直接把显示缩略图采样当作唯一配对事实。该新反例未证明历史 93/94 事故由缩放引起；原来的 `seal` 历史污染证据仍成立。

证据：`.gradle-coordinator/research-evidence/r2-final-probes/` 内完整 XML 与 stdout；探针源码及注入脚本在 `.gradle-coordinator/pairing-preanalysis-probe/`。两项实验均未把失败改成通过预期。原有 cold 首槽测试继续保留，作为缺输入先猜布局的反例。

### 下一批必须落实的内容

设计方向收敛为：**固定原图采样输入 → 提前取得局部必要事实并证明闭包 → 唯一章节配对 → 有限呈现保护 → 真实绘制进度**。本批已经验证最后两层及若干关键规则，确认四个现场目标的局部窗口可行，并量出采样内核成本；以下仍需实现和验证，不能冒充完成：

- 共享扫描步骤的通用闭包认证器，携带源序、opening 和左邻宽图状态，未认证不能猜成最终分组。
- 原始坐标、未裁剪、固定分析颜色空间的早期样本生产者；不能用当前 SSIV `onImageLoaded` 无 bitmap 参数的回调假装已拿到原始像素，不能用反射或额外假绘制绕过生命周期。
- DIRECT、PREFETCH→ACTIVE、已加载章复用的幂等接线，以及可验证内容身份下的派生缓存、失效和恢复。
- 同一 Android 运行环境中冷旧下载／热缓存／冷跳／连续翻页的首个正确像素与双页完成时间对照。此项尚未执行；主机 0.10 ms 提取成本不能代替实际体验。

当前文档与原型是可复查的进一步进展；没有生成或安装修复 APK，不能宣称用户设备上的 BUG 已修复。

## R3 执行记录：固定原图采样开始

用户已明确要求按设计第 7 节从 R3-01 开始执行。实验代码基线为 `4d65d3d67bd98cf8916edcd597976927972f7e0e`，开始时工作区干净，无活动 Gradle 或已连接设备；主工作区设计基线 `98ed60e65d3c75a31a8fa51adcf1444cc11bfe52`，用户 `docs/human/` 保留。

新实施代理继承切换后的当前模型，负责 R3 主要实现与串行 Gradle；上一模型的代理未恢复。预算沿用设计第 7.2 节。干净成本基线 worktree 为 `D:/Codex/worktrees/reader-pairing-r3-baseline-20261012`，仍为 `4d65d3d67b`。

仅使用已安装 API36/google_apis/x86_64 镜像新建专用 AVD `mihon-pairing-r3-20261012`，数据位于实验工作区忽略目录。配置为 1280×800、160dpi、60Hz、2 核、1536 MiB、Swiftshader；没有复用或清理其他 AVD 数据。创建工具提示多个既有镜像缺 `devices.xml`，但新 config 与镜像路径实际已生成；这不作为启动／ART 通过证据。主机当时约 4 GiB 空闲，重型 Gradle 与模拟器成本测量串行。

23 张原始 WebP 按既有取证 manifest 逐张核 SHA-256，暂存于实验工作区 `.gradle-coordinator/pairing-r3-inputs/`，总计 3,849,732 字节；原图及机器路径不提交 Git。`inputs.json` 保存此研究输入的尺寸、原图侧向结果与字节指纹，缺输入时 native probe 必须失败。

R3-01 初始红测 `.gradle-coordinator/pairing-r3-sampling-red` 实际执行 3 项，新增 3.5% 内侧区域覆盖断言因旧边缘 sampler 只采最外侧而失败，另 2 项通过；红证据已由实施代理保存。此时尚未完成新采样实现、APK 或模拟器运行，不能把已准备环境写成验收通过。

### R3-01 审查中的具体修正

新增独立审查代理同样继承当前模型。连续审查确认新采样不能令 legacy edge-only 入口也扩大到 3.5% 条带：8192×12000 原图的旧细边可在 4 MiB 输出限制内采样，新内侧区域则超过上限。两端改为保留各自取样计划、共用区域执行／回收函数；新 original 资源不足仍返回未取得证据，不降低上限或退回整图。首次新增区域覆盖测试随后改为验证新 original 能力；旧 API 以独立大图回归保护，不能把旧 API 不支持新侧向能力描述为旧评分 bug。

实际依赖 `image-decoder:41c059e540` 的 [Kotlin 工厂](https://github.com/tachiyomiorg/image-decoder/blob/41c059e540/library/src/main/java/tachiyomi/decoder/ImageDecoder.kt) 在 `newInstance` 内对 stream 使用 `use`；当前 sampler 外层也拥有源流。这在 Windows 不自关流的 decoder boundary 中不会显现。新增测试模拟真实工厂关闭输入，独立复现双 close；采样器将向工厂传不关闭底层的 borrowed view，由外层唯一回收原流，native probe 的实际源 close==1 断言保留。

颜色约定已读取同一精确版本的 [native wrapper](https://github.com/tachiyomiorg/image-decoder/blob/41c059e540/library/src/main/cpp/java_wrapper.cpp) 与 [WebP decoder](https://github.com/tachiyomiorg/image-decoder/blob/41c059e540/library/src/main/cpp/decoder_webp.cpp)：无 displayProfile 时 target 为 sRGB，源 ICC 经 CMS 转换为该目标；此为源码约定，仍需实际 JNI bitmap／像素验证。生成的 400×600 合成 JPEG 带 JDK 线性 RGB 的 488 字节真实 ICC APP2，四个原始坐标的独立线性→sRGB golden 与 JDK tagged raster 的 getRGB 一致；这只校准夹具，Desktop 的 region lease→PNG→Skia 及 Android JNI 仍须各自通过真实行为断言。源脚本与外部输入留在忽略目录，合成测试资源可进入 Git，漫画原图仍不提交。

### R3-01 实际 ART 红绿与组件结果

原生运行只使用专用 AVD，宿主／测试包为 `.dev`／`.dev.test`。首次 `r3-art-initial` 两方法因 shell 暂存目录的归属而 EACCES，尚未到采样，不能当行为红。仅修复该研究目录归属；虚拟机 adbd 临时 root 后恢复 nonroot，测试仍在宿主应用 UID 下运行。采集脚本一度把日志写到未忽略目录，已校正并把原记录完整移入忽略 evidence；没有提交采集文件。

随后取得两项真实行为红：

| 运行 | 宿主 SHA-256／测试 SHA-256 | 实际结果 |
|---|---|---|
| `r3-art-access-fixed` | `396453006b288834a84f827f71749946f0355e568cf117bbea91623435064d64`／`8aa764e5b73e267b959df38e5d19389de0324800a280e75e6d4bf392130ba7eb` | 2 方法，WebP 左边缘不等价失败；ICC 通过 |
| `r3-art-crop-alpha-red` | 同一旧宿主／`8eaf1201014bc7582994a2e021a08267551779f834dae82d6ecfb78c22e19900` | 3 方法，WebP 与完整原图不等价、独立 alpha PNG 失败；ICC 通过 |
| `r3-art-corrected` | `1391830b7c28980f621b97a43baa1f2e2a4bba38d95747262c834f703f4b83c1`／`1f3a5a3f8507a22f2a25f31f4921f7519399a6e4e983b66e602a0359fbb656fb` | 3/3，通过；runner `OK (3 tests)`／终态 -1，实际已安装 APK 字节哈希与 manifest 一致 |

WebP 证据为首张 `08-111` 的 18/50 左边缘点不同，三路 JSON 保存 original、legacy 和同 JNI 完整解码值。底层 WebP 对 crop 起点按偶数对齐，而旧执行器用请求的奇数原点索引；新合并计划暴露此偏移。区域执行器在 Android adapter 内按偶数扩展并留固定 chroma 上下文，按真实扩展 bounds 索引、扩展面积仍须 ≤4 MiB；共享数学点与评分阈值没有修改。

独立 PNG 直接编码 RGBA8，不经 Bitmap premul 量化：原始左侧 `0x804080C0`，旧 native 读为 `0x8080FFFF`，两区域 Bitmap 的 premultiplied flag 都为 true。采样工厂仅把真实 JNI 的 straight RGBA Bitmap 标成 nonpremultiplied，注入的正常 Bitmap 与显示 decoder／ReaderPageImageView 未改；纠正后 50 个左值和 50 个右值均精确匹配独立黄金值。它也纠正 legacy 采样的既有错误原生像素，不能说所有旧实际输出保持不变。

宿主验证组合：新共享计划 4 项＋既有边缘规则 12 项，共 16/16；Android 早期 9/9 后新增 codec even-snap 模拟回归成为 10/10；Desktop PNG/JPEG 6/6（包含真实 ICC region lease／PNG／Skia 四点独立 golden）；目标格式检查与 diff 检查通过。每个旧 red／中间失败都保留，未重新执行全模块或旧 98 项。原生纠正通过后重复取证仅采集输出、核 JSON，没有重跑已过的 ART。

纠正原型实际过程产物：[Debug APK](D:/Codex/worktrees/reader-pairing-research-20261012/app/artifacts/android/0.19.4-aex.59-vc77-4d65d3d67b-debug-3a3519e0d36c/Mihon-Fork-0.19.4-aex.59-vc77-debug-universal.apk)，production 输入指纹 `d2403fd9307028ff6874b0df84528e7f794de1a860ee89d87d1a44f09f659de3`。它只用于组件研究，没有安装用户真机，也不作为正式修复 APK 交付。

### R3-01 成本与放行边界

证据在实验工作区 `.gradle-coordinator/research-evidence/r3-art-corrected/`：`runner.log`、`run.json`、`native-sampling.json`、`native-alpha.json`、`sampling-summary.json`。主代理和独立审查各自核了实际 runner、APK 哈希、161 条数据及 alpha 精确值。每页 stream 打开／底层关闭各一次，各轮总读入 3,849,732 字节。23 原图冻结页侧、同 JNI 完整／legacy／新路径边点与独立 BitmapFactory 对照全部通过，95 的显示下采样反例继续为 1/2 UNKNOWN、4 RIGHT。

环境为同一 API36/x86_64/Swiftshader Debug；两轮预热后七轮×23 张，**OS 文件缓存已热，不是阅读器首像素，也不是基线／候选打开和翻页对照**。

| 实际组件阶段 | p50 | p95 | 最大值 |
|---|---:|---:|---:|
| 打开原流、原图计划、创建 decoder、两区域 decode、取样／释放、分类、返回总计 | 19.302746 ms | 25.410202 ms | 27.020430 ms |
| 两区域 decode 合计 | 18.332401 ms | 24.360665 ms | 26.124342 ms |
| 取样／区域回收及间隔检查，不含最后分类 | 0.338132 ms | 0.674641 ms | 4.503184 ms |
| 源流关闭后的最终分类与返回 | 0.039226 ms | 0.080691 ms | 0.207937 ms |

两区域 decode 占总计时约 95.1%。单个采样输出 Bitmap 的实际峰值为 209,152 字节；不代表 native 内部、完整对照图片或阅读器总内存峰值。此数据支持后续减少必要取证范围、复用已确认标量并接入已有预取，不能凭它写“无新增可感知等待”。

独立审查结论为 `PASS_FIXED_INPUT_INTERFACE`，可进入 R3-02。下游必须继续覆盖：扩 context 会令旧 legacy 的部分极长图（例如 64×250000）更早到达 4 MiB 限制，新 original 的大区域也会不可用；Desktop ImageIO 区域路径的 WebP 支持尚未实际验证。这些不能变成 Computed UNKNOWN、永久单页或永久 Loading，后续门卫接线前必须核与处理。尚未完成通用闭包、真实三入口或整条用户路径，无用户设备 BUG 修复声明。

R3-01 源码与测试已提交实验分支：`54d847c771`，父为 `4d65d3d67b`，提交后工作区干净；它将已验证的原始采样接口作为下一批基线，未合入主干。实施代理随后发生一次流连接中断，主代理核实提交、差异与协调器均已完成，再恢复同代理继续 R3-02，没有重跑／复制本批工作或新增代理。


## R3-02：通用认证接口与原图证据闭包

实验提交 `23a5582e329eec51566313be4f2ebf1b9152d797`，父为 `54d847c771`，6 文件、1407 行新增／93 行删除，包含同一证据与认证能力的共享生产扫描、owner/session 接口及 28 项内聚回归；大于估算规模主要来自显式状态与失败分支、完整格式以及逐项行为测试，不按文件机械拆分。没有图片、外部探针或机器配置进入提交；主干产品未合入。

实施代理连续三次在远程 compact 时断连，没有产生 R3-02 diff 或测试进程。主代理核实状态并收回实现，沿用原独立审查者；没有因此新增代理、重做 R3-01 或并发 Gradle。2026-10-13 用户撤销耗时限制，授权账户余额不低于 60,000 credits 时继续；旧 8 小时追加询问不再是待决条件。实际账户余额不写入本仓库。后续主代理不读取图片，目视检查由子代理返回文本及路径。

### 生产接口与证明范围

- `canonicalPlan` 与认证器共用 `ReaderCanonicalPairingScan` 的同一生产 transition，保留全章 ordinal、opening、预留及左邻宽图。有限 U/R/P 入态不同则向左扩；使用游标 memo 避免每次左扩重扫整个后缀。被丢弃的前缀 unit 不再为其物理单页槽位请求无关页侧，transition 保持不变。
- `ReaderSpreadSamplesOwner` 区分未分析、Computed UNKNOWN／null、可恢复页侧／边样本读取失败。标量在有界原始样本淘汰后保留；缺边的重新取样失败单独记录，不抹掉页侧，也不撤销已有完整 scalar。原图边来源必须为两份 original 样本，legacy 样本不能冒充或覆盖它。
- 证书绑定完整页序 revision、设置／手动 revision及实际依赖的内容、原始头、页侧、边 scalar；不以全局 fact revision 使无关事实撤证。批次先检查全部原始能力，再原子接受并一次失效计划；拒收批次不发布其第一半。
- Session 的可信原始头与显示事实分离。原图 UNKNOWN 覆盖旧显示 RIGHT；显示裁边／缩略尺寸不改原始几何。EXIF 已消费于头尺寸，用户 rotation 仅分类一次；复制 rotation 却不重算 state 的输入拒收。手动恢复未完成不能认证，失败沿用明确阻塞／本会话默认恢复；关闭的 session/owner 不发行证书。
- 已知真实宽图只需其有效原始头即可证明 FULL，不等待无关前缀页侧。已收敛窗口先认证，不为可选跨边剪枝先取无关 lookbehind。重叠 manual cluster 在最近已知无效候选／强制单页处截断实际必要依赖。

### 红绿与有效验证

首先 API 缺失导致编译红，该结果不当作行为红。随后的 `r3-certification-behavior-red` 实际 5 项／1 失败，具体复现原图 UNKNOWN 仍保留旧 RIGHT；纠正后同范围绿。新增审查反例分别保留 batch 部分接受、legacy 来源污染、manual 过度前缀请求、未恢复手动意图／disposed owner、lookbehind 无谓依赖、必要 header 失败、淘汰样本重取失败、显示尺寸冒充原图、宽图依赖和旋转几何的 focused 红及后续绿，未删除历史反例或重复旧 98 项审计。

最终冻结验证为 `.gradle-coordinator/pairing-r3-certification-integration.json`，终态 PASSED／exit 0；紧凑证据在实验目录 `.gradle-coordinator/research-evidence/r3-certification-integration/summary.json`，含实际 argv、时间、6 份源码 SHA-256 和原始 XML。独立审查者重新计算哈希并核对全部 XML，结论 `PASS_R3_02_INTERFACE`。

| 实际范围 | 结果 | 限制 |
|---|---|---|
| 新认证器＋实际 owner/session 28 项 | 28/28 | 含四故障窗口 16,384 次原始 owner 前缀排列；卷 9 头尺寸 980×1393，其余卷 8 为 980×1403；边变量为合成像素的生产评分，不分发漫画原图 |
| 已有 canonical 原型 10 项＋距离 lookup 2 项 | 12/12 | 复用完整生产扫描作为 oracle，检查图像历史／前缀预留及热查询 |
| Android 原生 holder 两项 | 2/2 | Windows Robolectric/native Skia 的真实 View/bitmap 绘制，保护半侧曝光后伙伴可达与晚到宽图像素不移动；不是 ART 阅读器耗时 |
| Desktop wiring 5 项＋运行时进度 2 项 | 7/7 | 保护现有真实消费者；新的持证显示门卫尚未接入 |
| 6 文件定向严格 Spotless Apply/Check 与 diff | PASS | 中间格式错误修正后执行严格规则；一次机械布局操作临时不检查行长，未当作严格验收，最终没有规则豁免 |

加强 124→125 生产 scalar 后，旧五页证书失效，认证器向左扩展，并与完整 oracle 一致。长无重置前缀的 1024 页用例实际执行生产 step，工作量不超过 4×页数，需扩至真实封面边界；这不保证额外读取在 16 页可选预算内完成，也不证明冷阅读耗时符合门禁。

### R3-03 前仍需处理的输入及呈现缺口

实际 Desktop 探针 `pairing-r3-desktop-webp-capability-red` 通过正常 Skia 路径读取已核 SHA 的 08-111 WebP（980×1403），但 ImageIO 无 WebP provider，真实区域 lease 的 `sampleOriginal` 返回 null，1/1 行为失败。XML 已保存在 `.gradle-coordinator/research-evidence/r3-desktop-webp-capability-red/`；这是已证实的能力缺口，不能写成 Computed UNKNOWN 或直接启用会永久等待的门卫。

R3-03 须落实真实 original producer 与 DIRECT／PREFETCH→ACTIVE／已加载复用接线、当前／将露出邻帧的持证准备、可恢复反馈和取消；原图 sampler 的扩 crop context 大于 4 MiB 的情况仍未解决。可先检查 Desktop 普通 decode 是否能在同一受控原图生命周期提供等价原始像素，但未实测不能假定 Skia 子集读取不解码整图；不擅自增加依赖、fork 或放松资源限制。

当前只放行共享接口，尚未完成 R3-03/04，也没有把新的阅读门卫打入 APK。R3-01 ART 3/3、组件 p95 25.41 ms 的证据仍有效，但不能代表实际 intent→正确像素对照。没有安装用户真机，三起现场问题仍不能宣称已经修复。

### R3-03 进行中的补证与 R3-04 夹具

2026-10-13，DIRECT／PREFETCH→ACTIVE／已加载复用三个实际 ChapterLoader 入口的 focused 测试 3/3 通过；原始坐标分块取样的 Android sampler 11/11 通过，`pairing-r3-bounded-original-green` 终态 PASSED。Desktop 普通 lazy Image 复用探针 4 项中 WebP／ICC／EXIF 三项通过，透明 RGBA 一项失败：预期 `0x804080C0` 实际 `0x804080BF`，UNPREMUL 输出无法恢复内部预乘量化。`pairing-r3-desktop-normal-evidence-green` 的失败保留，不放宽输入等价门槛；下一候选是在正常完整解码链内复用一次受预算约束的原始 UNPREMUL bitmap，尚未验收。当前没有开启持证显示门卫，也未增加宿主 APK。

主代理已准备 ignored `pairing-r3-download-fixtures/manifest.json`：卷 8 的 176 页夹具含 18 张冻结原图，卷 9 的 **合成 176 页代理章节**含 5 张冻结原图，另有 176 页无重置前缀的纯合成控制章节；各有普通目录与 CBZ。其余页均明确标为合成，固定灰边没有页侧锚点和有效连续边方差，中心二进制 badge 区分页码。所有 CBZ 条目 CRC／SHA 已核，23 张原图与输入 SHA 全部一致。独立审查者仅自行读取图片、向主代理回传文本，指定三图抽查通过；中心 badge 外像素均为灰色，未污染取样边缘。该检查不代表 DownloadProvider 命名、ChapterLoader 实际选择或首像素性能通过；后续运行必须走这些真实接线。图中印刷页码与 readerPage 不必一致，定位以 manifest 的阅读器页码为准。

后续窄验证 `pairing-r3-desktop-codec-evidence-green2` 4/4 通过：显式正常 Codec 解码的透明 RGBA 原始点完全匹配，另覆盖 WebP、ICC 与 EXIF；不包含最终 EXIF 显示矩阵、实际原生解码复用、峰值和延迟验收。原 lazy Image 的透明像素红保留。检查还发现大图先经 FULL_PAGE preview 才启用 region tiles，不能通过新增 16 MP 硬限制破坏既有预览入口；超预算大型 WebP 的有界能力仍未解决，不宣称全格式通过。

Android 原型 Adapter 已接原始分析 producer 和持证绘制门卫。`pairing-r3-original-producer-metadata-green` 的实际 XML 为 6/6：三入口／晚 EXIF 完整恢复 4 项，原生资料不足拒票据／READ 失败到现有 Retry 恢复 2 项，无失败、错误或跳过。首次恢复测试的虚拟时钟 deadline 失败不算行为红；实施者改用真实 IO 等待，并在旧 Adapter／Viewer／Holder 上取得正确的 READ 原因缺失行为红，随后恢复当前实现。

同一轮 Android 局部独立审查**暂未放行**，两个窗口已交原实施者做受控回归：未知尺寸时读取异常可能早于分析 request，或配额失败只记录在页侧分析、未被 dimension 认证消费，可能一直 NeedFacts／无票据；首个分析完成后换源、其余分析仍等待时，单页早期空校验不能证明最终批发布仍绑定真实 encoded 能力。后者为源码可达窗口待复现，不能写成现场已发生。还需证明证书成员／slots 与首次原生 frame 一致，完成取消／预算及既有半侧／晚宽图像素补验，并闭合 Desktop 消费链。尚未构建 R3-03/04 APK，也未开始实际翻页／首像素 A/B。

上述窗口后续取得实际补证：`pairing-r3-load-before-header-red` 复现 optionalWork 通知到任务重排的忙循环，线程栈记录主线程 CPU 约 201 秒、elapsed 222 秒；仅通过协调器停止该验证，终态 CANCELLED／130，**不是 JUnit 失败或通过**。修正终态阻塞仍入队后，`pairing-r3-load-before-header-cause-red` 得到有限 JUnit 1/1 正确红，真实 READ 被误报 RESOURCE。前移请求能力并让缺尺寸认证消费原始失败原因后，`pairing-r3-load-before-header-green` 1/1 通过：READ／RESOURCE 分支各只读取一次，五轮调度无忙循环，现有 Retry 后各累计两次读取并恢复，位置不变。

`pairing-r3-final-physical-batch-red` 1/1 实际证实：第一页完成后更换真实 stream、第二页仍阻塞且普通事实订阅尚未重绑定时，旧结果仍被接受为 Computed。最终发布按稳定故事序持有两页实际 content 锁，重新校验原始 fact／encoded 能力，才原子接受 owner 批次；`pairing-r3-final-physical-batch-green2` 1/1 通过，拒收整批过期结果，恢复订阅并经现有 Retry 后新内容认证成功，逻辑位置仍为 2。首次修复验证的等待超时随后改为等待真实 publication job，没有改变拒收期望。以上为专项补验，最终锁／回调边界及完整消费链尚待同一轮审查闭合，不能据此放行 R3-04。

实施代理在稳定工具边界保存唯一紧凑恢复回执并结束该 turn，随后沿用原代理和回执恢复，没有新增代理或重新执行 R3-01/02。Desktop 正常 Codec 的实际 raster 4/4、真实 ONLINE route 的 producer→cert→native pixels→SQL 2/2 已通过；目录直开 route 原本不写 SQL，不能拿它当 SQL 进度失败或通过依据。Desktop 当前仍需实际 READY lease／decodeKey 和最终 session 能力批校验、区域资源与成本补证。

该恢复点涉及 25 个未提交文件，超过审查估算范围：同一共享证据契约必须接通两端正常解码、原始分析、证书、物理槽位与既有重试／进度，平台接线及行为测试需一并评估。按完整生产链保持内聚，未为压缩文件数复制规则或拆开不可独立验收的代码；对应风险为内容能力跨异步任务失效、资源生命周期和首次绘制许可，按上述专项及最终同轮审查收口。

恢复后的 Android 首簇已冻结：`pairing-r3-android-cluster-refactor` PASSED，`research-evidence/r3-android-cluster-refactor/` 保存真实命令、日志和 XML，主代理独立核 XML 为 10 项、失败／错误／跳过均 0；两文件严格 SpotlessCheck 与 diff 检查通过。包含原生 PNG producer→cert→ticket→prepare／draw／release 两项，首次 unit 成员／slots 与证书相同，保留像素与 bitmap 身份、未画伙伴及晚宽图可达性。旧夹具故意缺可信 partner header 的门卫拒绝属于旧测试前提不成立，不算产品布局红。

另外五项预算／取消专项与受其影响的 READ、未知尺寸和批发布三项均复验通过：真正挂起读取的 15 秒期限和真实翻页未取消旧请求各先得到产品红，再加入剩余预算 timeout 与对应 owner-location 撤销；原 location 16 个额外页、两 decoder 峰值、32 MiB source、刷新不续预算、实际 region 挂起后 Retry 等待物理释放和退出回收均有定向证据。测试清理时未排空取消 job、以及虚拟 Main 期限越过真实 IO 的问题单独修正，不当产品红，也未降低拒收／READ 期望。整体 R3-03 尚未完成或提交，没有构建新的 APK／运行 ART；首图性能和三起现场故障仍未获本轮完整验收。

2026-10-13 同一轮独立审查复核 Android 稳定簇，结论为 `ANDROID_R3_03_KNOWN_BLOCKERS_CLOSED`，仅关闭原有 Android 阻塞，不放行完整 R3-03/04。审查核对冻结命令／日志／10 项 XML、原红证据、未知尺寸失败与现有 Retry、终态阻塞不重排、原 location 期限与真实导航取消，以及两项原生首次成员／slots、像素保护和伙伴可达。最终批按故事序持最多两页 content 锁，全部真实能力有效后才调用 owner 原子接受；锁内无 suspend／IO，未发现反向多页锁。Desktop 仍在闭合真实核心与模型订阅滞后的取证窗口，最终批内容预检、资源及成本尚未完成；没有据局部绿构建 APK 或开始 ART 对照。

Desktop 后续补证：原实施代理两次短恢复后仍报告长上下文影响高风险推理，保存唯一回执并停止产品实施，主代理明确收回后接管；未新增代理。普通入口先取真实 core 已 Ready 的内容而非滞后 Queued 投影，受控 READY 抢先消费后继续处理其他准入页并完整退出预算。SQL 校正为每次实际已绘集合的 settlement，而非每页一行；观察真实回调并继续原 tracker，每行核 max 页、次数与幂等。一次复验只等 last_page_read=2 提前结束（右页单独已绘也可到 2），随后改等完整真实已绘集合和对应落库，未降低双页断言。

真实 core 替换／延迟 UI 绑定取得旧样本批误收红，session 私有锁内核 generation／Ready／attempt／ref／provenance，纯 owner 原子提交，UI 发布锁外；显式 Retry 后原始取证先推进既有 pipeline attempt，无须先创建新 holder。审查发现 metadata 早于最终批接纳，随后普通 metadata、单页 READY 和有界 header 都补真实 core 预检，两个受控红保留。进一步真实复现当前物理内容被写入旧逻辑绑定，增加绑定核验：允许首次 materialize 尚未同步的空 ref，拒绝已有 ref／attempt／provenance 不符。当前 `r3-desktop-binding-capture-green` 两条真实呈现／进度类 15/15，无失败／错误／跳过；完整 R3-03 尚未闭合审查。

区域原图取样的两个正确红为整条固定点包围区超限与 mixed-alpha 蓝通道少 1；共享分块保留坐标，每次实际区域请求／输出不超 4 MiB，使用采样专用直通 sRGB RGBA bitmap；`r3-desktop-bounded-alpha-green` 11/11。真实 native cache clear 后已确认标量和露出像素保留，正常解码计数及 SQL settlement 不增加。23 张冻结原图、每图两次预热后 20 组交错 A/B 共 460 对：旧 normal raster p50/p95/max 9.975/12.255/14.633 ms，新 normal raster 12.973/15.407/17.674 ms；配对差值 p50/p95/max 2.949/3.898/5.611 ms，登记的同时存活 raster 峰值 10,999,520 bytes。`r3-desktop-native-cost/native-cost.json` 保存原始数组；该峰值不是全进程 native RSS，耗时不是 Android intent→像素。

定向回归中 shared certification 30/30、Desktop wiring 6/6 通过，既有单侧 Native→SQL 用例失败：auto-off 尺寸准备被伙伴完整正常解码占住。关闭自动匹配时优先复用现有有界 header，完整信息不足才走原始正常解码。另以真实带 EXIF 的 JPEG 输入得到接线红：原始 40×60 被错当 normalized，应为 60×40；统一 header、READY 和 normal producer 的坐标，用户 rotation 仍单独保留。`r3-desktop-header-fast-exif-green` 3/3，随后的 `r3-desktop-final-behaviour` 四个受影响类 27/27。前述旧 header 控制改为实际有界 lease 读完后延迟正常回调，保持 auto-off、真实 Retry、旧逻辑绑定和拒收断言；不强制 header 路径等待已不需要的完整 decoder。

严格 29 个文件格式在新增分块文件名和长行清理后通过（`pairing-r3-format-strict-final8`）；新增文件按类名改为 `ReaderBoundedSamplingRegion.kt`。中间仅排版时关闭长行项以落下其他格式，再以项目原规则完整检查；没有留下规则豁免。随后 EXIF／header 与绑定修复仍需补最终严格格式、受影响 Android 定向复验、同轮最终审查及提交。未构建新宿主 APK／运行 ART，三个现场问题仍不宣称已修复。


R3-03 最终补验：跨 owner 的 release 合并窗口已实际复现。第一个 owner 的刷新已入队后离开章节窗口，第二个 owner 的 release 被单一 scheduled 标志吞掉，canonical 已为双页而 Adapter 仍留两个单页。`r3-cross-owner-release-red` 保存正确红；刷新积累当前待处理 owner，执行时检查仍在章节窗口的任一 owner，退出清理集合。`r3-cross-owner-release-green` 1/1 通过，并纳入最终原生回归。该窗口是受控接线故障，不能反向声称它是卷 9 现场唯一原因。

现有认证 Retry 反馈也取得真实控件红：图片 native Ready 但认证 READ 失败时，点击实际 ReaderErrorBinding.actionRetry 恢复认证后，旧错误 overlay 因不会再收到第二次 onImageLoaded 而仍可见。`r3-real-retry-widget-red` 保留正确红；前三次夹具前提／页身份校正单独记录，不当产品红。holder 只跟踪和清理认证错误，普通解码错误仍沿用原路径；认证未恢复时真实 onImageLoaded 不得提前隐藏反馈。`r3-real-retry-widget-green` 1/1 及最终回归通过。

最终 XML 核验：`pairing-r3-feedback-final-focused` PASSED，Android 三入口 4、sampler 11、原生绘制／预算／内容能力／实际重试控件 12、既有普通错误控件 2，合计 29/29；`pairing-r3-desktop-final-refactor` PASSED，正常原图 5、sampler 7、实际呈现／SQL 9、wiring 6，合计 27/27。均无失败、错误、跳过。冻结目录为研究 worktree 的 `.gradle-coordinator/r3-android-feedback-final/` 与 `r3-desktop-final-refactor/`，保存命令、日志、XML 和核验计数。最后 29 个任务文件按项目原规则严格格式检查 `pairing-r3-format-feedback-final` 通过，`git diff --check` 通过。尚待同轮独立审查闭合与本批提交，仍未构建当前宿主 APK、启动 R3-04 ART 或宣称真机修复。


R3-03 同轮最终独立审查结论 `PASS_R3_03 / R3_04_GO`，最后实际控件反馈及此前内容身份、锁内纯原子提交、失败原因、取消预算、原始 alpha/ICC/坐标、native slots/像素保护和跨 owner 可达性均无未关闭实质阻塞。研究提交 `0c56d359d392227c1d477d41954ed702f07d4885`，29 文件、3975 新增／647 删除，父为 `23a5582e32`；测试与双端生产接线属于同一证据到原生绘制协议批次，规模和高风险边界按专项证据审查，没有复制算法或机械拆分。研究树提交后干净，主干未合入产品。最后 29 个文件 SHA 冻结在 `r3-android-feedback-final/source-fingerprints.json`。

进入 R3-04：原独立审查者结束 R3-03 职责后，复用为真实下载 instrumentation 实施者；主代理对其新增探针独立核验并仍是唯一重型 Gradle／设备计时协调者。原实施代理因长上下文无法稳定恢复保持停止，没有新增代理；此角色调整不增加已有代理／审查轮预算。先做真实 DownloadProvider→ChapterLoader→DownloadPageLoader 的目录及 CBZ 冒烟和 GPU 提交观察，再运行已冻结的冷／热、顺读／回翻／快翻及最坏前缀对照；仍只有基线与最终候选两次宿主 Debug 构建额度，不操作用户设备。


### R3-04 运行前冻结与探针核验（尚未 ART 验收）

精确基线 Debug 已由统一入口完成：`4d65d3d67b`，APK SHA-256 `976a2ad91c271c231245591277eac6dfebc2bcc68749da2701fc403c14569e05`；研究候选为 `0c56d359d3`，APK SHA-256 `40c59a2f81a59c3f41497098b1cdc4eb617eeca5d8067eccc1f3925ed65cf9f1`。包名、证书、versionCode、SDK、ABI、JDK、Gradle 和构建配置指纹核对相同，production diff 均为空；Debug 不递增正式版本、不安装用户设备。基线／候选构建命令、日志和 artifact.json 冻结在 `.gradle-coordinator/r3-art-comparison/`，完整原始对照表 `comparison-matrix.json` 在 ART 前冻结。至此原图两份＋基线＋最终研究候选四份宿主 Debug 额度已使用，测试 APK 编译不增加宿主额度。

真实下载探针只新增 instrumentation，目录／CBZ 通过独占 storage、真实 DownloadProvider 命名与虚构 DB 记录，ReaderActivity 真实开章后必须断言实际 DownloadPageLoader；未注入 ReaderPage 列表。原图标量 reference 由子代理在主机离线读取生成，528 页及各 archive SHA 全核，reference SHA-256 `2f179fd97d5597061daaccdbd75999ee06c73db50bbb0fa4fbefb0d565daeef3`，主代理未读图。目标进程不会在意图开始前解码漫画作参考；只读 seed marker 和标量，冷定义仍为进程／派生分析冷，不是 OS storage 冷。

主代理独立核验发现比例匹配可能把相邻合成页误认，修为 8 个页码 badge 位全必验且不可被裁掉；两个真实 reference 负例 8→9、93→94 拒绝。GPU frame commit 后 PixelCopy 逐页核真实绑定、实时内容身份、slots、locked／drawn 回执与固定原图点；双正确必须来自同一 buffer 和能力，不拼接两个 frame 的左右页。首图／双正确记录校验完成时刻的上界，GPU 提交另列；encoded/nativeReady/analysis 的轮询观察也是上界，未伪装成 decoder 内部精确事件。实际 stream 开关及 native 总峰值暂不可得，明确标 NOT_AVAILABLE，不能据此宣称资源矩阵全通过。

探针最终源码 SHA-256 `8956ecf511f7b8bd46da885ad657a8d1c1dfe3f3ac6832db43a184602bae331f`；候选测试 APK SHA-256 `15600fdcf6f71d111d267b7a55a3221103bc11c4f75aecf6ede9b50f9775ccfd`。候选 test compile、唯一文件项目原规则严格 Apply／Check 和 assemble 通过；两次格式失败仅阻止包装，未当行为红／绿。中间 layout 排版不作为最终门禁，最终没有规则豁免。driver 用明确 JUnit 一项成功回执、本轮 JSON 和独占结果文件判断；valid／0-tests／missing-output 三个 fakeADB 协议用例通过，后两项均失败退出，不能读取旧结果冒充运行。基线正在编译完全相同的 observer 源码，尚未启动模拟器／运行 ART，目录与 CBZ 仍 NOT_RUN。


第一轮 ART 真实运行：基线同 observer 测试 APK 包装通过（3m14s），APK SHA-256 `e05939a1e456e7aaaaddd1442bff9f5e62849c160f4c8784fe569f24129cd1a6`。专用 AVD 身份／serial 核对，API36 x86_64，1280×800、160dpi、显示 mode 60.000004Hz；启动日志明确 `Increasing RAM size to 2560MB`，实际 `/proc/meminfo` MemTotal=2,532,300kB，因此过去请求参数 1536MiB 不能写成实际 runtime RAM。两 APK 使用同一实例；没有用户真机连接／操作。

候选目录与 CBZ 的 control-no-reset-176 第 8/9 页均实际 DownloadPageLoader、双正确通过；目录首图／双正确 GPU+PixelCopy 校验上界同为 1891.557ms（仅一条冒烟，不是p95）；两页各72/72点、8/8强制badge和实时物理能力通过。记录为 `r304-candidate-control-smoke-directory.json`、`r304-candidate-control-smoke-cbz.json` 及各 runner/run 元数据。尚未批量对照，不能据冒烟报性能通过。

同一候选无重置前缀冷打开第126页，预期126/127，实际达到16个attempted、active空、位置sequence=0，20s观察结束仍无first／both正确像素，结果 `NOT_CORRECT`，驱动退出1。`r304-candidate-control-worst-prefix.json` 保留完整失败，没有伪导航续预算，也没有删慢／失败样本。它说明当前候选不能满足所有冷入口；不把此合成最坏场景冒充三起用户漫画现场已复现。

目录冒烟另观察到11 attempted但newlyReadBytes=0。沿真实源码只读证实：DownloadPageLoader为Ready页仍通过ContentResolver新开InputStream；sampleOriginal读取原factory，未接readpermit，JNI exact java_wrapper调用read_all_java_stream；Ready分支跳过loadFactPage，而finally将完成写成cacheHit=true、0bytes。CBZ的archive mmap也不能证明页级重新读取／解压免计。故这是实际可达的预算漏计量，不能把Ready／本地／OS cache当已有标量缓存；探针本身stream计数不可得，尚未宣称已测到11流真实总bytes。已交同实施者补Ready真实文件17MiB+17MiB的受控正确红、现有permit接线及header/full-header边界，主代理仍是唯一重型Gradle并独立验收。

基线 cold8 的native Ready/draw已观察到，但GPU回执没有完成最终像素校验；同进程重开8随后两页全部正确。`r304-baseline-control-smoke-directory.json` 整体失败保留，冷段未计性能；当前不能分辨真实旧帧/配对故障与观测在ticket变化后未重试。将以当前实际drawn/locked/bound能力在下一次preDraw重新注册GPU观测，仍禁止伪绘制／markDrawn，并补actual frames诊断。没有根据该未定位失败声称基线无限Loading／作为候选性能收益。模拟器已仅对核对过的owned serial正常关闭，再补focused；四份宿主额度用完，新增修复宿主将在具体代码与验证可审阅后单独处理。


Ready 文件计量专项取得正确红：真实17MiB+17MiB源分别在两条测试读到35,651,944B，fresh+complete header读到34,078,720B，即32MiB+512KiB；三项均因实际超限失败，非编译／夹具失败，冻结 `r3-ready-file-budget-red/`。把已有permit贯通默认optional loader、fresh／完整header及新开原图Source，reserve／returnUnread在真实读前执行，known header提前返回无开流；借用buffer与外层physical use保证唯一关闭，JNI吞Java异常返回空decoder时以exhausted优先RESOURCE，用户取消仍上抛。相同3项绿通过，随后原项目严格7文件格式以及受影响viewport完整类28、sampler11、producer4、普通widget2、真实HTTP2、共享预算3，实际合计50/50，失败／错误／跳过全0（不是此前预估32）；未重跑模块完整或全量。`r3-ready-file-final-focused/` 保存真实XML、argv／log、核验计数与sourceSHA。主代理独立核对期限时钟单位、同cap身份、借用／close、取消和错误边界后，研究提交 `fcad996867d330b8cc90d05bbbc43d426ec6fe1a`，6文件296新增／44删除（含严格排版），父为 `0c56d359d3`。这是代码及focused修复证据，尚非修复APK ART通过。

基线观测修正只改测试，不改基线production：共同observer V2 SHA-256 `853bae1f2ba3eef2348357f28f1d0f51e9a3d35de6a9e2c426fe0f507cc97448`，基线测试APK `0ac93450fd9f3befc5c91100eca7a146858943117c4f62a4aa52368e3094f532`。仍需真实current frame drawn／locked／bind才从preDraw重新注册GPU观察，没有markDrawn或伪准备。baseline cold8、同进程重开8及CBZ8均一buffer双正确通过，两页72/72点与8/8强制badge、实时物理身份和位置合格；cold／hot各记录一次 GPU_CALLBACK_CAPABILITY_CHANGED_OR_COPY_FAILED 后重观察成功，支持旧probe遗漏重试窗口。旧无copies超时不当产品红或性能样本。冷一次1665.722ms／热一次841.002ms仅冒烟，与旧candidate不同observer版本不做配对统计；真实20组矩阵未运行。旧证据与新 `r304-baseline-control-observer-retry-directory.json`／`...-cbz.json` 分开保留，未覆盖。

四份宿主 Debug 上限已实际使用，追加一份 `fcad996867` 修复宿主的请求在代码／50项验证可审阅后提出：仅专用模拟器、保留已有产物、不发布或安装真机。截至此记录尚待用户回复，不以credit充足或等待时间当批准；独立基线观测、必要focused和长前缀原理／优化预研继续，未停止整个任务。最坏前缀仍否决当前完整GO，不要求用户牺牲配对一致／已绘不移／实际无新增可感知等待三个目标。


### R3-04 追加授权、前缀分析成本与已知侧别优化

用户已明确批准超过原四份上限追加一份修复 Debug 宿主；仅专用模拟器，保留旧产物，不安装真机或发布。该第五份宿主尚未构建，先完成必要优化和有效 focused 验证。原文中的“待用户回复”是当时状态，现已解除。

真实 production NeedFacts 闭包实验保留了灰色无边界前缀风险：单目标 126 在冷态仍需 127 张原图分析；当前已观测 115～130 后还需 113 张、合计 129 张。不能从旧采样组件耗时直接推算真实开章延迟，更不能把预算重置或缓存预热当作解决冷态。原图 113 存在确定侧别时，旧扫描仍为无关的 112 请求原图分析，导致冷态 17 张超过 16 张预算。

优化沿用同一 canonical scan：在 forced/wide/manual 优先级之后，仅已有 Computed 原图侧别能提前排除候选配对；缺失、失败、header hint 不代替该证据，读取到的依赖纳入证书失效检查。正确红证实旧代码请求 112；绿测试覆盖双方向、双方排除和手工重叠优先级共 12 组合。生产闭包实验现显示该 reset 冷态由 17 降至 16 张；已有邻页观测场景由另需 3 降至 2 张。灰色无边界场景仍未改善，不把局部优化当完整 GO。

证据：研究 worktree 的 r3-known-side-veto-red/green 保留红绿日志；r3-known-side-shared-regression 保存严格格式、59 项共享测试及优化后闭包结果；r3-known-side-platform-wiring 保存 Android 47 与 Desktop 15 项实际 XML（零失败）。这些是共享规则与真实两端消费回归，不是全量或 ART 耗时验收。root 独立核对 manual/geometry 优先级、仅 Computed 依赖、完整 oracle 与局部认证同扫描及失效路径。

正常解码位图的只读观察已在未修改 production 的基线宿主取得：控制页 8/9、卷 8 页 125/126 均为软件 ARGB8888、sRGB、完整原图尺寸 980×1403、NORMAL、无裁边/源区域，每张分配 5,499,760 字节。尺寸相等不足以授权复用。新增探针按真实 production Sampling.plan 导出的左右各 194 个原始坐标，与子代理生成的 25 页全不透明无 ICC 原图 reference 做零容差逐点对照；尚未取得该精确比较的 ART 结果，复用未启用。参考图像仅子代理处理，root 只接收标量/JSON，测量期间不在设备重新解码参考图像。


R3-04 本批研究提交 `a07fbf16afedff89782145bde658aa257e732472`（已知侧别避免无关前缀分析，4 个 domain 文件，327 新增/1 删除；含正确红绿、闭包成本实验、失效与优先级回归），其父为 `50b7524af7`。未合入主干产品。

正常位图精确对照完成：基线 production 未变，instrumentation 源 SHA-256 `49789785d0f1291eb4f85ab119352393e24de5239ca5c129d1c09dd98449699b`；同源测试 APK 分别编译，基线 `3450cd842149c48f0874d86d0b79d6e29e6c45e79eaa8a1f8be8c1ebb1f8c601`、研究 `8d3a3b01240111e65e45063462afe46fbc10d3f8913faadf63b22bc21f5120dd`，测试 APK 不占第五份宿主额度。卷 8 全 18 张 111～128、卷 9 全 5 张 91～95 以及控制 8/9 共 25 张，每张 388 原始坐标均 exact/opaque=388、tolerance=0、maxChannelDifference=0。只读 app-owned WeakReference，无 SSIV 私有 bitmap、无参考图设备解码、无复用启用。探针提交 `f5c5ab50f5`，严格格式/两树 instrumentation 编译及实际 ART 逐点运行完成。

证据在 `r3-art-comparison/r304-baseline-exact-bitmap-control.json`、`r304-baseline-exact-bitmap-vol08-126.json`、`r304-baseline-exact-bitmap-vol08-sweep-quoted.json`、`r304-baseline-exact-bitmap-vol09-sweep.json`。前两个单次显示验证 CORRECT，两个翻页遍历保存 NOT_CORRECT（runner 确实执行一项且完整结束），只采用其中原图逐点观测，不把失败遍历作为配对/性能 PASS。首次卷 8 多 action 未执行，是 adb shell 将分号重新解析，独立 runner 失败日志保留；ignored driver 改用 shlex.join 保护 device shell argv，真实 printf literal 往返验证后再执行，未覆盖旧失败。AVD PID 68764 仅本轮专用实例，运行完成已 emu kill；实际请求 1536MiB 会被 emulator 提升为 2560MiB，不能报告成实测 1536MiB。完整冻结 A/B 耗时矩阵仍 NOT_RUN，第五份宿主仍未构建。


### R3-04 修复宿主与实际画面／耗时证据

已按用户追加一次授权构建第五份 Debug 宿主（统一入口 debug --offline），源码 `f5c5ab50f5a7aa67ec1bb7976e50620cf3fca72d`，生产输入指纹 `a9a11129b106c796e170dd5b05f855b55bb3586afba3f5e16bd6e927165e1908`，源码差异为空。APK 位于研究树 `app/artifacts/android/0.19.4-aex.59-vc77-f5c5ab50f5-debug-003203b8dd08/Mihon-Fork-0.19.4-aex.59-vc77-debug-universal.apk`；SHA-256 `f1ced8435f602ccdf852842f1f5c9a12acad2ee84752c055cb18a5a00a5b3865`，.dev 身份、versionCode 77、原 Debug 证书均核验。旧宿主保留，仅专用 emulator-5584 安装，不安装真机、不发布、不构建第六份。

真实目录和 CBZ 控制 8/9 均 CORRECT，各 11 个 attempted、实际新读 122,377B，旧同路径记录为 0；卷 8 125/126 单次冷打开 CORRECT，10 个 attempted、观察时 1 个 active、已读 1,660,907B，不能将观察快照说成所有后台工作已结束。证据 `r304-fix-control-directory.json`、`r304-fix-control-cbz.json`、`r304-fix-vol08-126.json`。

同源 V4 观察器、同设备／输入／阅读配置，按 AB/BA 交替，2 轮预热后完成 20 组冷打开＋同进程重开；40 次基线与40次修复包读数都验证实际正确双页。冷态指进程／派生分析缓存，不声称 OS 存储缓存冷。原冻结 matrix 保留，修复版绑定另存 `paired-cold-hot-fix-binding.json`，实际 RAM 2560MiB。统计 `paired-cold-hot-fix-stats.json` 用 nearest-rank p95（ceil(.95*n)），没有用坏基线合成 0 延迟。冷首次正确 p50/p95：基线 2804.09/3119.80ms，修复 3235.06/3373.52ms，p95 差 253.72ms 超预设 155.99ms；同进程重开 1623.46/1833.20ms 对 1999.05/2082.54ms，差249.34ms超91.66ms。首次正确和双页均正确分别保存。当前未通过原工程耗时筛选，不把工程阈值当用户实际感知验收。

普通连续翻页采用真实 pointer tap，间隔1000ms、各20屏，两端所有屏均 CORRECT。两页正确上界 p50/p95/max：基线463.44/525.48/530.82ms，修复508.27/549.71/553.31ms，p95增24.226859ms。实测 Choreographer 120 个相邻 frameTimeNanos 周期均16.666666ms，Display mode 60.000004Hz仅作对照；这是vsync回调周期，不是用户jank指标。热邻页增量也未通过原16.67ms筛选。

测试提交 `426f63576029e8452fc47836bb30a7b0d3a21b13`（2文件464新增/4删；三个 JNI／vsync 证据用例、复用流计数及真实输入工具，内聚 test-only 批，不改变产品行为）。strict scoped 格式与两树测试 APK 编译通过；三个精确方法 ART 均执行一项并通过。Android36外部目录 run-as/push 准备失败尚未执行用例，不当产品红；仅新三个方法转 private filesDir 输入／输出，旧三个方法保留。

真实 JNI 字节边界 `pairing-r3-ready-file-jni-budget.json`：两份80×120有效PNG补齐各17MiB，第一实际EOF读17MiB，第二只读15MiB且未到physical EOF，真实总读／metered均33,554,432B，两个source均open=close=1，第二抛真实IllegalStateException（JNI清异常后null），permit在仍active时exhausted=true、caller coroutine仍active，新第三page被同budget拒绝。adapter typed RESOURCE本ART未执行，复用既有真实adapter JVM映射作为组合证据，不在test复制分类后冒称通过。

解码成本 `pairing-r3-native-strip-full-cost.json`：2轮warmup＋7轮×23原图=161 retained，同一fresh production JNI decoder内交替 FULL_THEN_STRIPS 与 STRIPS_THEN_FULL；每轮388原始点零容差一致、plan.samples及既有hint一致、流唯一关闭、输出全部recycle。两条strip decode p50/p95/max=29.943/38.977/46.706ms；full一次42.485/50.068/58.615ms，paired full-minus-strip p50/p95/max=12.868/21.570/27.453ms，两个顺序分层结论一致。full分配5,460,560或5,499,760B，实际同时活full＋第二strip峰值5,708,912B，超过现生产4,194,304B上限；native总峰值NOT_AVAILABLE。仅同decoder组件对照，不是两个独立加载路径或intent-to-pixel。该方向更慢且更耗临时位图，排除，不放宽生产cap。

正常位图复用另有必须补证的色彩边界：ReaderDecodedPageCanvas能在prepareNativeImageDraw之前读取app-owned完整位图，即使gate拒绝也不产生drawn，故无固有“先画才可分析”循环。但SSIV decoder使用全局displayProfile，自定义ICC转换后的Bitmap仍可能标记sRGB；偏好为空不保证旧global profile已清除。后续必须取得decode开始的实际目标profile／代际以及严格encoded、metadata、holder generation绑定，未知资格直接fallback，不从旧可见内容callbackIsCurrent推出可发布原图事实。当前25图默认环境exact不足以开放一般输入；尚未实施复用。

快速输入证据保留但未据此判产品失败：旧300ms连续tap无实际连续翻页，后新增swipe驱动又被独立查出重复/倒序UP且tap缺UP，`r304-{baseline,fix}-fast-20-native-swipes.json`存在动画尚未完成、实际selection落后固定预期编号；这些尚不能形成有效快速成对延迟。冷／热与normal数据使用旧正确V4，不受新增V5手势缺陷影响。正在修正输入协议、采用真实PAGE_DOWN/PAGE_UP链连续验证并加入只读实例／bitmap时间线，旧失败不覆盖、阈值不放宽、不重跑无关矩阵。

现场编号的实际跳转／回翻／重开证据：以 production Sampling.plan.samples、SharedSpreadMatching.score 与 canonicalPlan 从冻结原图标量和控制页原始灰边完整计算176页 oracle；完整源序覆盖已核验。卷8局部111/112、113/114、115/116…125/126、127/128；卷9替代176页局部90/91、92单页、93/94、95/96。后者不是原书前缀，不声称真实第9卷92理应单页。修复宿主卷8冷开113→115→113→126及重开、卷9冷开93→95→93及重开全部CORRECT（`r304-fix-vol08-jump-back-reopen.json`、`r304-fix-vol09-jump-back-reopen.json`）；这些动作明确是 viewer.moveToPage 跳转链，不冒称真实连续输入。

R3-04仍需要定向优化：画面和字节预算已有实际链证据；冷／热／热邻页耗时失败、无边界冷前缀127页对16页上限缺口、快速原生输入与剩余失效恢复门禁未闭合。不能宣称完整预研GO、产品已合入或用户真机bug已修复。

### R3-04 有效快速输入、连续阅读与认证时间线补证

测试提交 `e393ff923811d77c61ceabd59ff7328c6740e3e9` 仅改变真实下载 instrumentation（314新增／12删除）。修正 pointer 的 DOWN／UP，并采用实际 PAGE_DOWN／PAGE_UP → ReaderActivity.dispatchKeyEvent → 当前阅读器 handleKeyEvent → 源序导航。Window.Callback 逐次转发原事件和原返回值，只读记录焦点、事件及选中页；不能从 sendKeySync 返回推断导航发生。两树测试 APK 编译和 scoped strict 格式通过，宿主生产输入未变，未构建第六份宿主。

初次按键没有到达应用。延迟按键期间实际 dumpsys 证实 focused window 为 `ImmersiveModeConfirmation`，top resumed app 仍为 ReaderActivity。仅专用 AVD 将原为空的 `immersive_mode_confirmations` 设置为 confirmed 后，真实单键 DOWN／UP 到达、handled=true，8/9推进10/11。此前无焦点失败不是产品导航红；保留原记录。V4 冷／热矩阵没有焦点观测，测的是应用 Window 的 GPU／PixelCopy 上界，不能补称整体屏幕可见或用户感知通过；不覆盖旧矩阵，也不据新环境信息自动重跑全部样本。

`fast-native-page-key-summary.json`：基线和修复包各连续20屏，实际选中页均推进20次，全部快照焦点为true、最终各20/20 CORRECT。绝对计划间隔300ms，中间不等待像素。两页正确上界 p50/p95/max：基线112.353/233.347/237.514ms，修复129.490/157.696/189.108ms；实际请求间隔基线298.298～301.994ms，修复298.170～364.338ms，最大计划迟到分别4.685/69.364ms。这是各一条20屏链，不是20组配对独立试验，不隐瞒修复包一次节奏超期，也不据此关闭冷开／重开和普通翻页耗时失败。

真实连续输入现场页段：`r304-fix-vol08-continuous-native-keys.json` 从111/112顺读至127/128再回125/126，9次导航均实际推进且CORRECT；`r304-fix-vol09-early-continuous-native-keys.json` 从替代夹具2/3连续读到92单页、93/94、95/96再回93/94，48次导航全部正确。卷9前段是合成替代，不是完整原书前缀，不能据此断言用户所说92、93的正确分组。另保留 `r304-fix-vol09-continuous-native-keys.json` 冷开92失败；未用顺读成功覆盖冷跳的认证缺口。

只读时间线没有支持目标页反复解码假设：基线8/9和修复8/9在观测窗口各只有一个 image/native/bitmap 身份。修复目标原始分析与 native Ready 之后，认证仍补前缀索引4、3、2、1。`r304-fix-cold-certification-trace.json` 最后索引1缺失观察在1822.898／1836.636ms，1860.321ms首次观察 Certified，attempted=11、active空、新读122,377B；纯认证查询约0.12～2.4ms。查询调用已核实的纯 `certifyPairing`，不签新request、不续预算、不伪绘制；这些都是轮询观察上界，不是JNI内部精确时间，单次trace不构成配对性能因果证明。后续优化应针对真实闭包取证和安全复用，不重复已排除的完整解码方向。

第五宿主的无边界冷126仍 NOT_CORRECT：16个attempted、active空、新读181,906B，`firstCorrectVerifiedNs=0` 是没有正确首图的哨兵，不是0ms延迟。真实任意冷跳的固定16页上限与所需完整前缀之间缺口仍在。R3-04保持未完成；专用AVD本轮已正常关闭，研究树干净，主干仍未合入产品修复。

### R3-04 未闭合项的最小优化设计（只读，尚未实施）

沿用原审查代理只读核对 `e393ff9238`，没有新增代理、构建或产品改动。既有161组Native3记录的成本分解保存为 `native-existing-component-breakdown.json`：文件缓存热态的JNI创建 p50/p95/max=0.618/1.428/3.409ms，两条区域解码29.943/38.977/46.706ms，原始点提取0.135/0.304/2.765ms，plan.samples纯计算0.036/0.122/1.091ms。不是CBZ成本或用户首图测量，不能把减少开流直接承诺为消除约250ms迟滞。

优先研究合格正常位图的标量复用：在现有gate之前按同一production plan取点，不等待drawn、不持有bitmap；要求完整未裁切、原始header尺寸匹配、软件位图、采样点alpha255、解码时固定默认sRGB目标的有效证明。还须绑定encoded identity、captured metadata与decode generation；未知资格直接走现有原图取证。global profile可延续旧值，故偏好空和Bitmap sRGB标签不能作为证明。资格、内容替换、取消、淘汰及fallback均须实际生产接线的红绿测试；当前25图exact证据只证明默认环境的样本，不是普遍资格。

次级候选是借用DualPagerPageHolder已经完整读取并保留的encoded Buffer clone，避免fresh文件／CBZ读取。借用必须受当前能力和生命周期约束，detach／替换拒收；不得新增整份readByteArray副本，clone共享segments延长的存活内存也要登记。它仍需JNI区域解码，不视为主要耗时问题已经解决。已准备的缓存候选可先作为一批原子接纳、重算需求，再启动缺失页；已经发出的双页批继续执行最终整批物理内容校验，不因awaitAll有等待就拆掉既有原子边界。

调度候选仅允许在已知可靠边界、完整保守依赖跨度（包含真实会露出的邻帧）及可靠encoded长度上界都证明落在16页／32MiB时填充第二槽。缺少上界不推测左邻；卷8冷闭包恰16，额外取112可能造成预算回归，需专门红绿覆盖。不得退款已读字节、制造location或自动重试续预算。无索引灰章冷126需要127份事实，此局部调度不适用。

持久标量索引只保留设计支路，未实施：复用ChapterCache既有DiskLruCache editor和淘汰，派生记录使用独立命名空间，包含真实encoded SHA-256、算法／记录版本、几何／方向／旋转与Computed侧别；有序两页指纹绑定边分数，明确区分Computed null和缺失。完整写后commit，失败abort；损坏、版本和身份不符按miss重取，不新增DB迁移或后台服务。SHA沿已有完整Buffer、下载写入或实际sampler输入计算；外部SAF／CBZ无可信内容凭证时须读实际字节验证并计入I/O／预算。路径、mtime和会话编号不替代内容身份；首次旧下载无索引的分析成本不能预热后隐去。该设计需要先验证收益与身份边界，再决定是否进入原方案约定的缓存支路，不能据设计宣称三个原目标已达成。

先收口原矩阵缺口：仅在已有第五宿主上补实际内容替换、取消恢复与样本淘汰的定向instrumentation，不新增宿主或重跑性能矩阵。产品优化若进入实施，其现有JVM红绿可先独立完成；新的production ART最终需要另一个宿主，现第五份额度已经使用，第六份尚未授权。

### R3-04 自然样本淘汰与回翻补验

研究提交 `9b2b62fa9c` 仅增加只读 instrumentation 观察：真实容量16的向量缓存、原始向量集合、页面分析、有序边分析和当前可选任务数；不清缓存、不改容量、不注入事实。定向 Spotless 与 `assembleDebugAndroidTest` 通过（协调器 `pairing-r3-natural-vector-eviction-direction-test-assemble`）；测试 APK SHA-256 为 `447af95903ca3a530a23482fe5ceaef62897d1668e3ef3d2ba1fd21026a9881b`。宿主仍为第五份 `f5c5ab50f5`，没有新增宿主或产品变更。

专用 API36 AVD 的真实 DownloadPageLoader 读取虚构176页控制章：8/9起读，PAGE_DOWN前进20屏至48/49，再PAGE_UP回退20屏至8/9。`r304-fix-natural-vector-eviction-return-direction-retry.json` 及 runner 回执证明初始加40次翻页共41个正确GPU画面、40次实际选择变化、JUnit 1/1通过。缓存中仅剩37～52页向量，8/9的向量和原始向量均已自然淘汰，但两页Computed分析及8/9的Computed(score=null)仍保留。回到8/9时正确显示，当前location的attempted与active均空、新增可选读取0B、当前可选任务0。汇总为 `r304-natural-vector-eviction-summary.json`。

首轮只读探针未取得私有direction，故没有具体边证据；补正反射后首次调用又在JUnit开始前遇到ADB连接重置，原失败回执保留，不计产品失败或通过。随后上述独立文件名的完整补验通过。该结果只关闭运行期样本淘汰项：未统计普通图片I/O，不宣称零I/O；不存在持久分析缓存，不宣称其损坏恢复通过；每屏等待正确GPU结果，不充当300ms或成对耗时测试。取消恢复、内容替换仍待实际ART覆盖，原耗时与16页预算缺口仍未关闭。

### R3-04 JNI 读取中取消与重开恢复

研究提交 `00cb84bd62` 只增加单方法 instrumentation `directoryDownloadCancelsInFlightOriginalJniAndRecovers`，复用第五宿主。定向格式与测试APK编译通过（协调器 `pairing-r3-native-cancellation-test-assemble`）；测试 APK SHA-256=`eda6e72b85a19ba5a9c4d1c46e2f802bffc212fa01849bfaa3f9b27650cc8fa5`。真实 DownloadPageLoader 的页2流原样转发，仅在真实 native factory 内物理EOF处设有界测试阻塞，header读取不阻塞、不替换字节、不注入事实；安装时原分析为NotAnalyzed且请求尚未发出。

`r304-fix-native-cancellation-recovery.json` 和完整runner证明 JUnit 1/1通过。finish前真实matchingJob active、未取消、未结束，剩余预算14,193ms；真实Activity销毁后该同一Job已取消但未结束，物理流尚未关闭，测试暂停1,190.173ms，排除15秒预算自然到期。释放EOF阻塞后该Job完成，命中的JNI实例创建1次、回收1次、物理流close调用及返回各1次，没有在取消后继续区域解码；optionalWork和foregroundWork均为0。重新打开真实ReaderActivity，DownloadPageLoader的8/9两页PixelCopy正确。

这补齐受控JNI读取中的取消恢复场景，不能据此认定用户现场黑页均由取消造成或已修复；真实内容替换仍待验证，耗时退化及无边界长前缀超过16页预算的问题保持未关闭。未重跑性能矩阵、未增加宿主或操作真机。

### R3-04 内容替换发现 RESOURCE 跨导航滞留

新增替换instrumentation复用实际JNI EOF控制点，真实备份并覆盖下载目录的002.png为冻结012.png，原新SHA分别为`95baed5bd48045a2302800000fd049f1f570dcff01b3257abf2bd4115f064ca7`、`d126ae41b84517235e2cfbdc83d0e7f0ce025e882f997fcba4f189a10bca4cb9`。最终像素参考明确映射2→12、3→3；两份参考的8个mandatory坐标相同、3点颜色不同。首次测试代码错误地按普通参考点数组下标比较（72点对55点），替换前越界；原失败`r304-fix-native-content-replacement.json`保留。改为仅按mandatory坐标严格匹配，未截短或放宽PixelCopy判据。

定向格式与修正后测试APK编译通过，协调器`pairing-r3-native-replacement-oracle-test-assemble`，测试包SHA-256=`bcfe27db0c33eb866dbfa45bd68aecb1636f0b7b08e1b3d09d0d2fb7358f8bea`；仍为第五宿主。扩展gate后的取消回归`r304-fix-native-cancellation-after-replacement-probe.json`实际JUnit 1/1通过，后续仅oracle比较修正，不影响该证据。

`r304-fix-native-content-replacement-oracle-fixed.json`实际JUnit 1/1失败，原始记录保留：旧页2请求`2:1`在JNI读取中，文件实际替换后内容为`2:2`；生产store已传播新身份、旧票据owns=false，放行旧JNI后旧分析未被接纳，旧实例create/recycle/physical close各1。随后真实Viewer导航到2/3，location由0变1，却等待20秒无正确画面。末态页2的encoded和native均Ready，但分析明确为新`2:2`的`RetryableFailure(RESOURCE)`，holder scope为NONE；新预算attempted/active为空、新增读取0，optional任务0，新内容采样JNI创建0。finally确认原下载文件已恢复。

已证实阻塞路径：`SharedDualPageSession.goToPage`建立新预算却保留`sampleFailures`及RetryableFailure；Android `preparationNeeds`对RetryableBlocked只自动排入DIMENSIONS且NotAnalyzed的需求，RESOURCE导致新位置不再取证。旧批结束后原预算拒绝再次准入页2、进而记录RESOURCE，是由代码分支和原attempted证据支持的推断，未取得该reserve调用的逐次日志。该试验确认一种“原图Ready但分析不恢复”的机制，不能直接认定此前真机故障同因。

下一步窄TDD：新的有效导航仅解除RESOURCE瞬态分析失败，保留Computed/scalar、READ/UNSUPPORTED以及旧票据拒收；同一location不清失败、不更新预算。需共享规则、Android真实接线及Desktop受影响契约验证。原图身份边界已通过此试验，但自动恢复尚未通过；耗时及长前缀预算缺口仍未关闭。新的production ART验证必须在修复可审阅且focused通过后再申请第六宿主，第五宿主额度不能冒充可验证新代码。

### R3-04 跨导航瞬态资源失败的定向修复

研究提交 `ed09b77874`：仅在既有成功`goToPage`/`navigate`建立新位置时，清理RESOURCE页面失败与对应sampleFailures；保留Computed、标量边、READ/UNSUPPORTED等非RESOURCE失败、旧票据拒收及同一location预算。production两个共享文件新增10行，没有Android独立规则、自动续预算或后台重试。批次5文件491增15删，主要为真实内容替换的instrumentation和回归；这些测试分别提供故障证据与共享/原生接线验证，属于同一个可审阅恢复修复，未因行数拆出独立框架。

红绿证据：共享新增方法先因新location仍RetryableBlocked正确失败；Android新增方法初次存在JUnit断言参数顺序编译错误，修正后曾在夹具前置超时（起始页4不能保证目标两页同批），均保留、不计正确红。仅将夹具起始页改回既有批处理用例的2并加实际解码诊断后，真实PNG替换→旧位置RESOURCE→真实moveToPage路径在新位置认证阶段正确失败，进而在上述最小修复后转绿。共享新增方法包含两导航入口×两类缺失证据×三种失败原因共12组场景，仍计为1个JUnit方法。共享只读反射用于核查标量保留，绑定当前Android/JVM测试目标，不是可跨未来JS/Native运行的反射接口。

最终定向回归与格式通过：协调器 `pairing-r3-resource-navigation-regression`，共享7、Android7、Desktop2，共16个JUnit方法，零失败/跳过。Android包含替换后新PNG实际再次解码、认证及native holder新像素绘制，旧整批拒收、资源上限、既有Retry、邻帧未画伙伴可达及已画宽图稳定；Desktop复用实际model重试和内容替换接线。红/绿/回归XML分别冻结在研究树 `.gradle-coordinator/research-evidence/r3-resource-navigation-{red,green,regression}/`，组合证据不称全量通过。

当前第五宿主仍不含该修复，实际ART自动恢复验收仍未完成。下一最小动作是获准追加一份第六Debug宿主，通过统一构建入口、核验后仅安装专用AVD，重跑实际内容替换和受影响取消恢复；不重跑原性能矩阵、不操作真机、不发布或合入主干。R3-04仍未完成，原约250ms冷/热耗时退化及无边界冷跳16页上限缺口不因这项恢复修复而消失。

### R3-04 第六宿主实际 RESOURCE 恢复红绿对照

用户明确批准第六份 Debug 宿主后，统一入口 `python scripts/build-android.py debug --offline` 构建、verify及仅专用AVD安装均通过。源码为干净的 `ed09b77874f5392c61fd630a70329925e6c9da53`，生产输入指纹 `38d27fe48d8aa7dfd4c10b0f262b577dc78744e521b435bbe6a97e01ec22d853`。过程产物为 [第六份研究 Debug APK](D:/Codex/worktrees/reader-pairing-research-20261012/app/artifacts/android/0.19.4-aex.59-vc77-ed09b77874-debug-69cd40546305/Mihon-Fork-0.19.4-aex.59-vc77-debug-universal.apk)，SHA-256=`1df86e3acc835065c70007d9414663a55267c989fedc865e81ae217635653117`；包名 `app.mihon.desktop.fork.dev`、versionCode 77、versionName `0.19.4-aex.59-9484`，连续 Debug 证书 `fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`。旧产物保留，第七宿主未授权。

第六宿主初次内容替换 `r304-sixth-native-content-replacement.json` 虽通过，但主代理独立核验发现：测试未等待实际RESOURCE产生就导航，存在跳过故障状态的时序可能。因此保留原成功记录，仅作为内容身份及实际像素证据，不用它关闭RESOURCE恢复门禁。测试修正提交 `a1e5abeb4b` 只增加50行instrumentation：在原有10秒截止内，必须从当前真实session观察新内容 `2:2` 的RESOURCE，确认仍为location 0、同一旧budget、页2已attempted、新内容JNI尚未创建且读取0B，然后才允许真实导航；不注入失败、续预算或增加等待上限。

定向格式和测试APK编译通过，协调器 `pairing-r3-native-resource-precondition-test-assemble`，测试包SHA-256=`ca0a341b4d0c611a41de25b217605102fd141a3058e6d4e67b1658073d70c216`。同一测试包、虚构下载目录和API36专用AVD，依次复用既有第五／第六宿主，没有新建第七宿主。启动期间一次ADB daemon连接超时，随后身份核对及统一安装验证成功，测试完整执行；不将该环境错误计为产品红。

- **有效红** `r304-fifth-resource-precondition-red.json`：真实RESOURCE前置全部成立；导航到location 1后仍等待20秒，断言“Actual new content pixels did not match the frozen oracle”失败。完整runner明确1项、1失败，结束码完整。最终新budget attempted/active为空、0B、无可选工作，画面不正确；不是编译或前置失败。
- **有效绿** `r304-sixth-resource-precondition-green.json`：相同RESOURCE前置成立；真实viewer.moveToPage建立location 1，新 `2:2` 分析转Computed，2/3两页通过冻结新内容PixelCopy参考。新原图真实读取11,247B，JNI创建／回收／物理关闭各1次，区域解码2次；optional和foreground工作归零。旧请求被拒收、旧JNI创建／回收／关闭各1次，两轮最终均恢复原下载文件。JUnit 1/1通过。
- 主代理独立断言上述前置发生在导航之前、相同旧预算及新身份、红失败位置、绿新内容及资源释放，汇总 `r304-resource-precondition-red-green-summary.json` 保存输入文件SHA。证据均在研究树 `.gradle-coordinator/r3-art-comparison/`，未加载图片进入主代理上下文。

第六宿主取消恢复 `r304-sixth-native-cancellation-recovery.json` 亦JUnit 1/1通过：退出前剩余预算14,289ms，真实JNI阻塞约1,165ms时Activity销毁使job取消，随后释放并唯一回收／关闭，没有取消后继续区域解码，工作归零，重开8/9实际像素正确。上述50行修改仅在替换分支，不影响该已通过的取消分支，故复用其有效证据，不重复无关运行。

**本批结论：跨导航滞留RESOURCE导致原图Ready却不再分析的缺陷，已在隔离研究代码及实际Android Debug链路中修复。** 组合证据为既有16项定向回归、格式／测试构建、第五红→第六绿及第六取消恢复；不是最终代码重新全量通过，也不证明用户历史真机黑页都由此造成。专用AVD已关闭，未操作实体设备、合入主干或发布。R3-04继续保持“需要定向优化”：约250ms冷／热退化、普通翻页增量及无边界长前缀16页上限尚未关闭。下一改进仍是本报告既有的合格正常位图／已持有编码数据复用与内容指纹派生索引预研，必须先证明输入等价、身份失效和收益，不能放宽三个用户目标或当前资源上限。
