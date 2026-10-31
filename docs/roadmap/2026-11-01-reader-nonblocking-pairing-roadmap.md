---
status: planned
date: 2026-11-01
---

# 双页阅读非阻塞配对迭代 roadmap

## 1. 新会话入口

唯一功能规格：[双页阅读非阻塞配对设计](../2026-11-01-reader-nonblocking-pairing-design.md)。目标是未下载长章冷跳时先显示，后台匹配在下一次主动翻页纠正，允许重复边界页而不自动漏页。

**当前只完成文档设计，所有产品任务未开始。新会话从第一个未勾选任务 NB01 开始。** 用户本轮只要求文档并计划另开会话实现；读取此文件本身不授予产品实施、推送或发布授权。

本文件是这次独立修复的执行计划，不启动旧双页 roadmap 的 DP02 完整发布矩阵，不重做已完成历史批次。与旧文档冲突时，采用设计第 3 节的有限替代；未列出的身份、资源、持久化、原生和验证约束继续遵守。按第一个未勾选项推导当前进度，不另建 `active-task` 状态或第二套 manifest。

### 接手时只核对这些事实

1. 读取仓库 `AGENTS.md` 及动作命中的指南；UI／架构、验证、进度交接是必读入口。先报告实际流程预算。
2. 核对 HEAD、工作区、构建协调器及任务进程。规划起点 `5e04aa34be6f64a6765e95a928c96588b9a6cb58`；因主目录发生并发提交／暂存，本次文档在基线 `e29dbd5946a42b48819137a346064b86fc1869fb` 的独立 worktree 完成。起点已有 `AppVersion.kt` 修改和 `.codex-remote-attachments/`，不是本任务内容，不回滚、不暂存。不能假定这些状态在新会话仍相同。
3. 若相关源码变化，只检查受影响差异，不重做全仓库调查。原诊断 2 项测试只证明旧门禁，不是本次实现的通过证据。
4. 确认源页编号：验收第 126 页等于 index 125。按设计处理“同组未绘伙伴一次精确激活后可主动离开”，不得为了防漏页引入无限重复。
5. 将 NB01 首个任务簇交实施子代理，明确共享代码和测试所有权；主代理负责接口、独立审查和集成。平台任务依赖共享接口，串行接续，不能两端另写配对规则。

## 2. 实施预算与边界

以下是下一会话实施时的规划估算，不是本轮已消耗或自动批准的额外费用。

| 项目 | 最小安排 |
| --- | --- |
| 技能／工具 | 优先项目原有工具与测试夹具；原生操作才按当前可用 computer-use 技能和平台指南加载；不启用 REA |
| 代理 | 默认 1 个实施子代理连续承担 NB01～NB03；主代理规划、只读审查与必要集成。普通路由按本机适用规则；最多 2 个子代理，不按平台同时写共享代码 |
| 并行 | 实施者写代码时主代理可核对验收与准备环境；共享接口 → Desktop → Android 串行。重型 Gradle 在同一 worktree 经协调器串行 |
| 独立审查 | 主代理未实施共享状态时负责 1 轮独立审查，接口检查与最终闭环分阶段记录；必要修复复审 1 轮。主代理若接管了核心实现，另由未实施者审查 |
| 验证 | 每个任务簇 focused 红→绿→必要重构复验；最终只补新变化及尚未覆盖的集成，不把累计成功称作最终代码全量通过；全模块／全量默认 0 次 |
| 交付物 | 共享与双端 production 修复、对应测试、与实现一致的内部文档、一个功能提交；体验产物按当次明确的日常验收安排提供 |
| 过程产物 | 现有协调器日志／JUnit XML；本 roadmap 的一份累计证据表。无需新生成器、逐任务报告、状态服务或巨型 diff 包 |
| 时间与主要成本 | 预计 1～2 个工作日；主要成本是双端帧身份／动画生命周期接线和定向测试，不是算法重写。执行前按实际基线和设备再估算 |
| 追加条件 | 新产品范围、预算显著超出、全量或正式发布、不可逆外部动作才另作决策；普通红绿与已知范围内的接线修复持续推进。环境问题先诊断，不靠反复重跑或换模型解决 |

本迭代预计触及共享呈现与导航、两端 adapter 和测试，可能超过 8 文件／400 行；这些文件共同实现同一个跨端显示协议，按责任保持内聚，不为行数切成不能验收的零散功能。完整自动匹配算法优化、同步迁移、全章索引及其他旧故障不纳入本次范围。

## 3. 功能任务簇（按顺序）

以下复选框是带测试的任务门禁，不是逐条产生空提交的指令；一个用户能力在 NB04 完成后统一交付提交。若因跨会话需要保留阶段代码，须包含对应测试并注明能力未完成，不用阶段提交宣称修复完成。

- [ ] **NB01：共享临时显示、稳定帧与不漏页导航。**

  **前置／输入：**设计第 3～5 节和 NB-A01～A12；现有共享 session／presentation／certifier／fact budget；真实 production 测试夹具。主代理先冻结可验证的不变量，然后由实施者写红测试。

  **修改所有权：**`domain/src/commonMain/kotlin/mihon/domain/reader/` 的 `SharedDualPageSession`、`ReaderDualPagePresentationOwner` 及必要 DTO／默认布局 helper；相应 `commonTest`。只在确有契约需要时修改其他共享类，不能重写 sampler、canonical 评分、持久化 schema 或 scheduler 策略。

  **必须先失败的行为：**200 页、第 126 页、只有本图几何可用且分析挂起时取得有效临时显示能力；该能力只能画正确内容；背景认证完成不改当前帧；126／127→127／128 可重复边界；只绘一页、反向、空绘出集合按规格推进；临时组与已认证组均覆盖“未绘伙伴首次停靠并更新锚点／页码、再次主动离开、不制造未绘进度”；退出／rebind 拒收旧回调。测试执行共享 production owner，不能复制算法。

  **最小实现：**分离呈现许可和认证证明；保留未知证据状态；复用默认规则；引入有界过渡重叠与准确 frame 目标；保留未绘伙伴的导航责任；保持实际绘制和结算身份。临时票据不得升级成 Certified，默认图组不写手动记录。已恢复手动设置／可信宽图继续优先，慢恢复不会挡图。

  **定向复验：**新增行为契约及受影响的 `SharedDualPageStablePresentationContractTest`、`SharedDualPageStrongRequirementsResearchTest`、认证研究／手动调整／进度中的具体方法。旧“未认证无绘制”测试改为分别证明认证仍拒绝伪造、临时合法显示可用，不直接删除旧安全断言。原 126 缺前缀反例继续保持未认证的预期。

  **退出条件：**共享红因正确、选定范围全绿；独立检查区分 logical page／frame 身份、手势取消、相同候选推进、重叠释放及进度；接口能够被两端直接使用。尚未接平台不得称用户黑屏已修复。NB02 依赖此门禁。

- [ ] **NB02：Desktop 真实跳页、逐槽显示与过渡接线。**

  **前置／输入：**NB01 共享契约固定；检查当前 Desktop 改动，保护 `c54d59babc` 的单页来屏保留及已整合回归；不顺带重构阅读器其他模式。

  **修改所有权：**`app-desktop/.../ui/reader/ReaderScreenModel`、`ReaderPagerNativePresentation`、`DualPagePagerViewer`、`presentation/` 以及实际需要的 image/publisher 接线；对应 Desktop 测试。一般不改 native 图形后端或扩展运行时。

  **红测和实现一体：**从真实进度条／production 导航提交到真实内容链，构造 200 页在线清单；前缀分析响应保持挂起、目标页真实编码图片可返回，断言未认证期间已有目标像素。逐槽错误／重试与旧画面共存；frame 定位不能用重复页首次匹配；127 在出屏与入屏两帧均有有效 lease；动画取消和连续新目标不回跳。确认加载请求本身不依赖认证许可。

  **定向入口：**优先复用 `mihon.desktop.ui.reader` 包中的 `SharedDualPageOriginalImagesProductionTest`、`SharedDualPageNativeStablePresentationTest`、`SharedSpreadDesktopResourceWiringTest`、`ReaderChapterTransitionPresentationTest`，以及 `mihon.desktop.reader.SharedDualPageRuntimeProgressProductionTest` 的相关方法；必要时增加一个聚焦本次契约的测试类，不复制 fixture runtime。

  **范围门禁：**覆盖 NB-A01～A07 的 Desktop 实际接线，以及 A08～A12 的受影响分支。可控网络仅测试本次已改变的成功、挂起、图片失败和重试语义；若实际改了 HTTP 解析，再补项目规定的协议错误矩阵。资源限制保留、停止增强分析后仍可读，不能靠放大预算或让所有分析先完成来换绿。

  **退出条件：**真实像素和进度断言通过、受影响格式检查通过，记录精确测试方法和红绿原因；不把 source 文本扫描、空容器或长时间等到认证成功当首绘证据。

- [ ] **NB03：Android 真实 viewer／holder 与相同导航契约接线。**

  **前置／输入：**NB01 共享契约和 NB02 暴露的接口问题已收敛；读取 Android 构建／原生指南，不直接照搬 Desktop 组件细节。

  **修改所有权：**`app/.../ui/reader/viewer/pager/` 中双页 viewer、adapter、holder 及必要 loader 调用点；对应 `app/src/test`。共享规则变化回到 NB01 同一 owner 修正，两端同步复验受影响方法。

  **必须先失败的真实行为：**进度条 126 冷跳，在需要的远端分析尚未完成时，通过真实 holder 的 before-draw 接口画出本图；不是给全部页面预填 Ready／header 后只等 Certified。旧 `uncertifiedOriginalNeverPreparesNativeSlot` 应保留原图认证不可伪造的含义，并新增合法临时绘制的相反场景。40 页全 Ready 的超过 16 页测试不能代替本轮在线 200 页验收。

  **最小实现与回归：**viewer 消费共享显示许可；按目标 frame 精确定位，修正 `indexOfFirst containsPage` 在过渡重叠中的歧义；holder 保留已绘像素、迟到伙伴／重试和每帧释放；触滑、点击、按键与进度条尊重同一源页意图。保留 page-list／loader／原图身份及内存门禁。

  **定向入口：**`DualPageViewportZoomTest`、`ReaderPairingPreparationIntegrationTest`、`ReaderPageFactProductionWiringTest` 及实际受影响的 progress／chapter-window 方法；用 production viewer/adapter/holder 进行挂起响应与绘制断言。支持 RTL／LTR，至少覆盖 A03～A07 的 Android 代表路径及 A08～A12 的变更分支。

  **退出条件：**所选 Android 方法真实执行且通过、格式通过；不得以 Desktop 通过替代 Android 接线，不把 JVM／Robolectric 结果写成用户设备已验收。

- [ ] **NB04：双端闭环、独立审查、文档与提交。**

  **前置：**NB01～NB03 完成，没有已知未解释的相关测试失败。先汇总证据，找具体缺口，再选择补验，不因为任务结束重跑整个 reader 或整个模块。

  **独立审查：**检查完整 production 链路中是否还有配对→加载／绘制的硬依赖、临时结果是否污染认证、重叠帧是否误选／释放、未绘伙伴是否丢失或导致导航死循环、手动恢复是否被静默覆盖、进度和旧回调是否有效。最多按预算进行 1 轮必要修复复审。

  **自动化收口：**验收表 A01～A12 各有真实断言；已有有效证据可按最终差异复用，新增改动只复验受影响范围。保留普通认证测试及单页／条漫的精确关联回归。格式检查覆盖改动文件；确认非零 tests、skipped 和环境失败单独记录。

  **受影响平台用户路径：**在当次授权的 Windows 日常 preview 和 Android 测试候选／实际运行环境，各走一次“在线 200 页→跳 126→目标先显示→匹配迟到→前进纠正→回翻→重试”的真实入口路径。优先使用既有可控在线测试源或合法脱敏图片，不下载用户作品整卷。平台、候选来源、配置、输入和像素／页码观察分别记录；若 Android 仅取得原生宿主测试而无设备体验，明确记设备项 `NOT_RUN`，不混称双端实机完成。macOS 不因 Desktop 共用代码而自动触发正式验收；也不能据 Windows 通过声称 macOS 已验收。

  **交付：**将本设计及架构入口改成与最终行为一致的状态；最终核对 git status，仅提交本功能代码、测试及必要文档，报告 hash。没有推送授权时不推送；有推送授权则按 Git 交付细则核对整个集合和固定 SHA。公共功能说明等用户验收本次具体候选后再更新。

  **完成判断：**代码／自动化、平台运行、用户验收分别报告。平台必做项受阻时可提交已通过验证的实现供续验，但该复选框保持未完成并列出具体缺口；不把环境缺失或“提供包了”当验收通过，不为此扩大到全量／正式发布。

## 4. focused 执行约束与已验证命令形状

以下只示范参数和精确过滤，不是要求重跑诊断测试。每次选择当项真实受影响的类／方法；一次调用只包含一个 Test task，多个平台串行。在同一 worktree 通过协调器执行；超时先查状态，不能重复启动或全局清理 Java。

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
# 原诊断中实际成功的过滤方式；实施时换成当前任务的方法，核实际 argv 与非零用例。
python scripts/gradle-coordinator.py run --key 'pairing-diagnostic-example' -- 'D:\Shell\Github\mihon\gradlew.bat' --offline --console=plain '-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' :domain:jvmTest --tests 'mihon.domain.reader.ReaderPairingCertificationResearchTest.sixteen observed control pages cannot certify an unseen prefix reset' --no-daemon --no-parallel --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'
```

Windows 使用经核对的实际 wrapper 绝对路径；隔离 worktree 必须换为该目录的 wrapper，不能仍指回主目录。带点号的 Gradle 属性整体引用；`./gradlew.bat` 在当前协调器的 Windows 命令入口曾被解释错误，未整体引用的 `-Pkotlin.compiler.execution.strategy=in-process` 曾被 PowerShell 拆开。这两次属于启动／参数失败，没有执行产品测试，不算产品红因。优先修命令而非改 production。

内存参数是本机诊断成功配置，不是新的项目固定上限；重型测试前按当前资源和项目规则确认。离线依赖缺失时遵守联网指令和真实错误边界，不通过扩大测试解决环境问题。格式命令按项目现有过滤能力限定改动文件，避免 `spotlessApply` 改写用户未提交内容。

## 5. 累计证据与交接记录

实施者在同一表中补充必要条目，不复制大段日志。每项至少注明代码基线／后续相关变化、具体方法、红因、绿结果、日志／XML和平台边界。没有执行保持 `NOT_RUN`。

| 批次 | 实现／提交 | focused 红绿与格式 | 平台实际路径 | 未解决项 |
| --- | --- | --- | --- | --- |
| 规划 | 本次仅设计文档；源码基线见第 1 节 | 1 轮独立设计审查；已修正已认证帧未绘伙伴的停靠遗漏；UTF-8／相对链接及 diff 空白核验通过；本轮产品测试 NOT_RUN | NOT_RUN | NB01～NB04 全部待实施 |
| NB01 | NOT_STARTED | NOT_RUN | 不适用：共享接口阶段 | 待实施 |
| NB02 | NOT_STARTED | NOT_RUN | Desktop NOT_RUN | 待实施 |
| NB03 | NOT_STARTED | NOT_RUN | Android NOT_RUN | 待实施 |
| NB04 | NOT_STARTED | NOT_RUN | Windows／Android NOT_RUN | 待审查与闭环 |

交接保留本次精确 HEAD／diff、实际运行中的进程和协调器 key；进程仍运行时先接续观察，不重启同一任务。模型或会话切换不清除已消耗预算和未完成门禁。下一会话获得实施指令后直接从 NB01 推进，不再次把已确定的用户优先级当开放问题。
