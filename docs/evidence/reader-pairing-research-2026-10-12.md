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
