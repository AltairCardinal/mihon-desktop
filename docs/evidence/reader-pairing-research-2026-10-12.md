# 双页配对修复预研：执行证据与放行结论

日期：2026-10-12。对应[完整设计](../2026-10-12-reader-pairing-determinism-design.md)。

**当前结论：完整方案未通过，不能合入或生成修复 APK。** 用户要求“读序无关”和“立即显示、后台不搬图”同时成立，本轮没有获得允许降低任一项的授权。共享原型的局部通过不能替代这两项。

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

这 33 项不能统一归为“旧测试过时”。其中既有旧策略明确依赖进入页／历史的断言，也有真实的呈现、迟到手动布局、票据和进度风险，以及本轮新增的硬条件失败。没有批量删除、禁用或降低旧测试来制造通过；完整根因分类和修复不在这个不可放行原型里继续扩大。

### 已运行的严格条件反例

`SharedDualPageStrongRequirementsResearchTest.cold ready page and warm complete content must have identical first physical slot` 调用真实共享 Session，两个会话使用同一内容身份、同一目标页。热会话已知道前页宽图；冷会话只有目标图，稍后才得到相同宽图事实。

以下前置断言通过：两会话均允许已就绪目标获得绘制能力并报告绘制；冷会话补齐事实后旧帧没有移动。最后“相同内容首次槽位相同”断言失败：**热会话 RIGHT，冷会话 LEFT**。因此即使逻辑分区后来收敛，仍不能满足用户要的首次结果一致。这是共享状态机的实际执行反例，不是本轮 Android 真机画面测试。

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

另外，源码审查确认原型尚有以下实质限制：

- `units()` 输出新计划，但正在屏上的旧帧仍可能保留旧布局；原生 adapter 更新可能重挂载，不能只看旧 ticket 尚存就认定像素稳定。按新计划 `navigate()` 也不能证明与实际旧帧边界一致。
- 当前旧帧被标记 stale 后，真实已绘制但尚未停稳的进度会被拒收；最终严格门禁已实际复现，不能声称已有阅读进度完全保持。
- 纯扫描本身线性，不代表 production 全链线性：真实 `ReaderSpreadSamplesOwner.distance()` 会线性查找 `identity.content`，整章逐边调用最坏为 O(N²)。测试的 O(1) lambda 不能证明 production 性能。
- 尚未实测双端持久索引、原图提取成本和真实设备首帧／翻页性能。旧下载已有文件也不等于已经具备稳定索引。

## 发布门禁与后续边界

本轮产物是设计、可复查的实验代码和测试证据。它不修改当前设备上的诊断 APK，不证明 Android ART／原生手势／厂商系统性能已经通过。

- [ ] 在缺必要邻图信息的冷跳场景，同时证明顺序一致、首图无额外识别等待、已露出画面不移动。
- [ ] 实际 Android 与 Desktop 呈现投影、票据、进度和手动布局回归全部通过。
- [ ] 索引无效、内容替换、重试、缓存淘汰以及快翻超过预取速度时仍满足同一契约。
- [ ] 对目标 Android 路径完成可复现的首图／翻页延迟和资源对照。

这些门禁未通过前，不承诺完整修复，不把原型 cherry-pick 到主干，不要求用户用放宽条件来换取“完成”。

本轮保留两项用户要求；结论是这版候选设计与原型尚不能同时满足它们。已验证的局部修复方向可保留，后续首先需要解决确定配对的必要信息来源，以及当前帧／导航／进度的完整契约，再讨论产品实施。
