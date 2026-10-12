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
