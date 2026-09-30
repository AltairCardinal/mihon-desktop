---
status: in_progress
date: 2026-09-28
---

# 同步进度稳定展示实施 Roadmap

## 1. 范围与启动条件

需求权威：[同步进度显示开发规格](../2026-09-28-sync-progress-display-design.md)，重点为第 13–17 节；UI 审阅基线：[双端 HTML DEMO](../prototypes/multi-device-sync/index.html?progress=continuous)，提交 `b652df9ccf`，用户已确认通过。生产代码核对基线 `02c48df981886d7e0b80722cd4fd8bc81a5a2ccc`。2026-09-28规划轮仅编写文档；2026-09-30按用户指示启动下列产品任务。

目标：Android/Desktop 的“书架 → 同步”及首次配置合并页面，统一使用固定七槽摘要、单条进度轨道与可展开详情。动作/ETA/日志更新不让正常运行中的摘要反复增减行；安全确认、等待恢复、失败处理和原操作入口继续真实可用。

范围外：同步协议、数据库迁移、确认/去重算法、估算器重写、后台唤醒策略、真实账号授权流程改造、密码安全方案、书架其他功能、通知中心重设计。Android 前台通知沿用固定标题/正文。HTML 原型不继续增加业务模型，正式实现不复制其模拟计时器或 `sent + applied` 口径。

本文件是已在隔离分支激活的产品 child plan，进度从首个未勾选项推导，不声明 `active-task`。启动时核对实际父执行计划和 worktree 占用，在不覆盖其他工作前提下登记唯一 `active-child-plan`；规划轮未修改父指针；本次仅在隔离分支挂接父计划，不修改 parity capability 状态。若当前执行体系没有父计划，由执行者明确挂接后启动，不凭文件名猜测正在执行的父任务。

开始实施前必须：读取仓库及 Desktop UI 规范；检查 HEAD 与本基线差异；保护并行密码原型、书架等改动；复核 `SyncPanelContent/Controller/ProgressFact` 有无新行为；固定规格 P1–P14、D01–D10 的测试入口。只复核有变化的接口，不重新盘点全部 130 条文案。

2026-09-30 启动记录：用户明确要求实施本计划。工作树 `D:/Codex/worktrees/dc4c/mihon` 启动时干净，HEAD `41e7e5ff1ef92b382de1b3134e11cf94ae1883f4`，分支 `codex/sync-progress-display`；本分支父计划唯一指针已挂接本文，不修改其他 worktree。相对规划基线，共享 controller/事实接口未改，新增同步 UI review wrapper 须保留。作者专项保持历史验收与待修复记录，不在本分支恢复。实施预算遵循第 4 节，实际验收及平台阻塞统一记入证据文件。

## 2. 任务与依赖

| 批次 | 用户可见交付 | 前置 | 主要修改范围 | 必测契约 |
|---|---|---|---|---|
| SP01 | 同步中看到稳定摘要和按需详情，MAIN/首次合并一致 | 规格冻结、启动检查 | presentation-sync、base/zh-rCN 文案、共享 Compose 测试 | P1–P7、P11–P13；D01–D04、D09–D10 |
| SP02 | 暂停恢复、终态与错误处理不丢入口，关闭重开不闪旧状态 | SP01 接口与组件稳定 | 同一共享组件、必要的 controller 局部修复、共享存储契约、双端 wiring 测试 | P8–P10、P14；D05–D08，并复验受影响 SP01 项 |
| SP03 | 两端正式版本可验收，证据与提交完整 | SP01/SP02 功能代码及独立审查通过 | 测试、构建/运行证据、必要文档及版本文件 | 完整 P/D 矩阵与平台门禁 |

2026-09-30：SP01/SP02在同一共享组件内连续实现，以一个内聚功能提交交付；定向验证181项、178通过、3项Windows条件跳过，唯一修复复审通过。批次与代码/测试一同提交后勾选；SP03正式发布和人工门禁仍未完成。

SP01 → SP02 → SP03 串行；它们共享同一面板和 controller，不安排冲突文件并写。SP01/SP02 为同一用户能力的两个可审查交付批次；不把映射器、资源字符串或单个测试类拆成没有产品入口的任务。

### SP01：共享稳定摘要、详情与真实状态接入

- [x] **SP01 完成：双端共享固定摘要与详情已实现、审查、验证并提交。**

**用户路径**：书架同步按钮 → 同步主面板 → 立即同步；以及设置完成授权后 → 首次合并。主卡固定呈现状态/主操作、确认量、稳定动作、单轨道、时间、说明和详情入口。详情内看到局部计数、请求比例、队列与日志。

**实现步骤与边界**：

1. 在 `presentation-sync` 提取共享投影与有界展示会话，按规格第 13/14 节消费现有事实；注入单调时钟。阶段/方向/范围变化清旧比例，800ms/2秒稳定规则、1秒普通数字刷新与立即状态更新集中实现。必须在本批实际接入 `SyncPanelContent`，不提交孤立 helper。
2. MAIN 和 SETUP/MERGING 接入同一个摘要组件；run 尚未创建或 fact 缺失时保留占位结构。生产确认数只取安全字段，终态优先级和暂停静态模式至少保持现有功能可用；不能把旧终态/控制入口先删掉等下批补。
3. 将阶段细节、正文比例和局部 ETA、队列及最近条目移入有界详情，保留现有分页与稳定 key。折叠默认不显示额外进度轨道；展开后保持滚动与焦点。同步期间重复的 header/queue/合并标题去重，空闲页仍保留下一次同步与队列入口。
4. 复用主题和原生卡片尺寸；窄屏/字体变化按配置重排，普通进度变化按七槽稳定。统一资源 base/zh-rCN，记录其他语言回退。更新旧三阶段/正文轨道的 UI 断言以检查新位置，保留其真实计数和确认要求。
5. 细化的终态结果归位和跨 controller 恢复放在 SP02；本批不得改变生产暂停、重试、首次导入或批处理语义，不修改数据 schema、transport、ETA 算法。

**红→绿→重构**：先以生产投影接口写虚拟时钟失败测试，再在真实 `ImageComposeScene` 挂载 `SyncPanelContent`，覆盖现有列表增行/局部 ETA 挤占摘要问题。确认因目标行为未实现而失败后实现最小组件，再清理重复渲染，重跑受影响测试。D01 的 799/800ms 与 1999/2000ms 边界、scope 变化立即撤销、null/0、传输 100% 未完成都必须有行为断言。

**验证**：新增 `SyncProgressPresentationTest`；扩展 `SyncPanelContentTest`、`SyncPanelOnboardingIntegrationTest`。真实 Compose 点击详情、加载更多、关闭/重开，测量固定配置中的卡片/按钮坐标；中英、深浅主题、Android 320dp 默认/200% 字号与 Desktop 窄/宽窗离屏截图。沿用 `-PsyncVisuals=true` 保存少量候选截图，注明环境，不当作上游基准。红绿阶段只跑这些 focused tests；不跑全量 Desktop 或正式构建。

**完成证据**：测试名→P/D ID→首轮失败原因→最终结果；MAIN/SETUP 两处 production 接线；普通事实变化不产生新增 DB 查询/写入，隐藏面板取消视觉定时；截图由主代理核对。SP02 所需投影接口稳定，未留下按钮空回调。审查/提交前不得勾选。

### SP02：恢复、结果归位与操作语义收口

- [x] **SP02 完成：等待、暂停恢复、终态和错误路径经真实 controller 与双端 wiring 验证、审查并提交。**

**用户路径**：运行中暂停/继续 → 关闭/重开 → 等待自动重试 → 成功或部分完成 → 查看原因/打开失败日志/处理待决定项；首次合并与批处理分别暂停/继续。

**实现步骤与边界**：

1. 固定主操作映射，复用 `PauseSync/ResumeSync/RetrySync/Synchronize/Authorize/BeginSetup`。PAUSING 和恢复阶段不提前启用继续，等待重试到零不由 UI 启动任务。首次合并和已选事项分别绑定 `PauseImport/ResumeImport`、`PauseBulk/ResumeBulk`；子任务等待原因与定位入口不能隐藏到用户无法发现。
2. 终态使用持久 run、匹配的 terminalSummary/failureLog；冻结历时、清 ETA 和活动语义。部分完成同时保留待人工决定、待确认批次及无法还原信息；日志入口默认可见，SaveFailed/打开失败分别反馈；公开仓库原因全文可达。
3. FAILED 默认“同步未完成”，只有 `retry_exhausted` 才说明耗尽。当前无显式无工作信号，零条 SUCCEEDED 仍为“同步完成”，零条 PARTIAL 不得变成“已是最新”；不新增持久字段来装饰标题。
4. 将重复 exchange notice 收敛进结果区，保留 bulk/setup 独立反馈和关闭消息语义。会话内不重复播报，重开只读终态；没有可关联运行的 notice 不得覆盖当前 run。详情会话状态按规格重置，空间/generation/run 变化隔离，旧 fact/旧日志不污染新运行。
5. 使用现有真实 controller、文件数据库、runtime 及已有 HTTP fixture 构造集成输入。仅为上述界面接线的真实缺陷局部修复 controller；不改变协议/安全回执/调度。如果必须改这些边界，记录证据并暂停扩展范围。

**红→绿→重构**：先在 `SyncPanelStorageContract` 与真实 controller→Compose 的路径写失败用例，再实施修复。仅在假的 panel 中断言 action 已记录不足以通过：点击后必须能观察实际 runtime/偏好/队列状态的正确变化，移除 production 接线应使测试失败。成功/失败日志打开继续验证真实平台 adapter 调用及失败反馈。

**验证**：共享存储契约同时由 JVM/Android 运行；必要时复用 `SyncRuntimeRestartRecoveryAcceptanceTest` 的文件重开夹具。扩展 `AndroidSyncPanelTest`（Robolectric 宿主）、`DesktopSyncPanelTest` 及相应 RuntimeWiring 测试的受影响用例；保留授权、返回、危险确认和批量处理回归。对 SP01 的修复只补跑受影响测试，不重复无关全量。

**完成证据**：D05–D08 全部有真实派发与恢复证据；平台文件打开与返回路径可用；安全/隐私问题、未确认量误报成功、恢复倒退等阻塞项全部关闭。独立审查覆盖 SP01/SP02 稳定 diff 后，各批连同测试及必要文档提交，才勾选对应项。

### SP03：跨平台测试、正式构建与交付验收

- [ ] **SP03 完成：必做平台验收与证据完整，正式产物及最终提交可追溯。**

2026-09-30自动化收口：完整Android及受影响模块、完整Desktop与格式通过；Windows/macOS正式构建及隔离TestMode通过，Android原证书code36正式候选已生成并verify。真实账号、原生Tab/系统读屏、实体Android运行与升级仍待用户验收，因此本项保持未勾选，不降低必做门禁。命令结果、正式产物与来源统一见[聚合验收记录](../evidence/sync-progress-display-2026-09-28.md)。

**前提**：SP01/SP02 focused 测试和独立审查通过，已冻结本轮产品 diff。实际执行前报告可用 Windows/macOS/Android 环境、预计构建耗时和签名状态；不要将历史环境记录视为当前可用。

**执行范围**：

1. 按第 3 节统一执行受影响模块完整测试与一次完整 Android/Desktop 门禁、格式检查。重复 full-tests 只在新增产品代码使原证据失效、且获追加预算后运行；必要 focused 修复仍按范围执行。验证场景包括 P1–P14/D01–D10，不能只以通过条数替代场景覆盖。
2. Windows 使用项目 Desktop 构建脚本；同一 diff 已有等价全量 JVM 证据时用 `build-only`，运行脚本自带的 production runtime 验收及 Test Mode。报告只引用日志 `Final unpacked EXE:` 的实际正式路径并确认存在。
3. macOS 使用匹配源码与 diff 的隔离环境按同一脚本构建，验证真实应用包与 Test Mode；不覆盖用户日常配置，不以 Windows JVM/浏览器通过代替 macOS。主平台完整测试证据不覆盖平台特有 API，仍需 Mac 受影响 focused tests/运行验证。
4. Android 按[构建与验收规范](../architecture/android-build-and-acceptance.md)执行 `build-android.py check --signing`、`candidate`、`verify`，正式签名/versionCode 遵守统一入口；不把 `app/build/outputs` 的中间 APK 当交付。构建不代表安装授权；不代操作用户实体设备。无证书或设备时明确待验项，不能冒充正式候选已完成。
5. 原生界面完成键盘、返回、读屏播报节奏、减少动画、字号与几何验收；使用离屏图和允许的平台工具，不读取 Test Mode 屏幕像素。真实账户同步由用户在可用正式候选中验收，自动化只用隔离数据/MockWebServer，不写用户仓库。
6. 将证据聚合在一份拟新增 `docs/evidence/sync-progress-display-2026-09-28.md`：基线/diff、P/D覆盖、命令结果、截图环境、正式产物路径、失败与待验、提交。按已有映射维护涉及的 parity 证据，不创设重复 capability 状态表。文档/版本/checkoff 随功能或最终验收提交，不另建纯状态推进提交。

**完成边界**：平台不可用、签名不可用、真实必测未完成时，该项保持未勾选并列出已通过和待验范围；可以交付现有候选供审阅，但不能说生产闪动 BUG 已全面修复。只因 HTML 审阅通过不得勾选任何产品批次。

## 3. 分层验证命令与证据要求

以下是执行命令模板，实际结果见聚合证据。Windows 验证前设置 UTF-8、`PYTHONDONTWRITEBYTECODE=1`、`$ErrorActionPreference='Stop'`；Android 必要时设置 `ANDROID_HOME/ANDROID_SDK_ROOT=D:\Android\Sdk`。境外下载按项目代理要求；已有 Gradle 任务运行时先查协调器，不启动第二个重任务。

```powershell
# SP01：只运行相关共享 UI 类；新类名以实现为准
python scripts/gradle-coordinator.py run --key sync-display-ui -- .\gradlew.bat :presentation-sync:jvmTest --tests "mihon.presentation.sync.SyncProgressPresentationTest" --tests "mihon.presentation.sync.SyncPanelContentTest" --tests "mihon.presentation.sync.SyncPanelOnboardingIntegrationTest" -PsyncVisuals=true

# SP02：共享文件数据库/恢复契约
python scripts/gradle-coordinator.py run --key sync-display-storage -- .\gradlew.bat :data:jvmTest --tests "mihon.data.sync.JvmSyncPanelStorageContractTest"
python scripts/gradle-coordinator.py run --key sync-display-android-storage -- .\gradlew.bat :data:testDebugUnitTest --tests "mihon.data.sync.AndroidSyncPanelStorageContractTest"

# SP02：真实双端宿主；类范围随本轮修改精确增补
python scripts/gradle-coordinator.py run --key sync-display-android-panel -- .\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.data.sync.AndroidSyncPanelTest"
python scripts/gradle-coordinator.py run --key sync-display-desktop-panel -- .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.sync.DesktopSyncPanelTest"

# SP03：共享 UI 完整测试、Android 完整测试及格式；其他改变过的模块同轮补全
python scripts/gradle-coordinator.py run --key sync-display-final -- .\gradlew.bat :presentation-sync:jvmTest :app:testReleaseUnitTest :test-desktop:test spotlessCheck -Pmihon.testBuildType=release

# Windows Desktop 完整测试一次；当前脚本没有内置协调器，使用唯一外层协调器
python scripts/gradle-coordinator.py run --key sync-display-desktop-full -- "C:\Program Files\Git\bin\bash.exe" scripts/build-desktop.sh full-tests
python scripts/gradle-coordinator.py run --key sync-display-windows-build -- "C:\Program Files\Git\bin\bash.exe" scripts/build-desktop.sh build-only

# Android 正式交付入口，不隐式安装
python scripts/build-android.py check --signing
python scripts/build-android.py candidate
python scripts/build-android.py verify --artifact "<本轮候选APK绝对路径>"
```

任务名需与启动时的实际 Gradle target 匹配，若变更仅 presentation-sync，不为凑流程运行 data 全模块；若 SP02 修改 data/controller，在 SP03 同轮加入 `:data:jvmTest` 与受影响 Android data 完整测试。macOS 命令在其隔离环境执行，不从 Windows 直接调用平台不适用 task。禁止用专项 init 脚本作为正式发布路径。

## 4. 调度、审查和成本边界

历史规划轮：主代理直接完成，0 子代理，1 次文档核验，不运行 Gradle/构建。当前产品实施按已声明预算，将 **SP01 主实现与 focused TDD 交给实施子代理**；主代理负责接口、验收和整合，不重复写委派文件。SP02 优先复用同一实施者，按前置串行交付。

当前完整实施预算为最多 2 个子代理：1 个实施者、1 个未实施目标代码的审查者。真实并行仅限稳定 diff 的只读证据检查与不写冲突文件的环境预检；重型 Gradle 由主协调者串行。独立审查 1 轮，在 SP01/SP02 稳定里程碑合并检查；阻塞修复复审最多 1 轮。未完成审查的批次保持未勾选；若出现必须在下游前审查的高风险协议/数据改动，属于当前范围外，先暂停重规划。

红绿次数以正确失败→最小实现→重构为一组，每批只跑对应 focused 测试；全量 Android/Desktop 在 SP03 集中一次。产物为生产代码/测试、方案必要更新、一个聚合验收记录及正式构建包；过程日志由现有协调器保存，不额外生成逐任务报告或大 diff 包。

粗估墙钟：SP01 2–4 小时，SP02 2–3 小时，SP03 2–4 小时；合计 6–11 小时，取决于现有夹具、编译缓存与平台可用性，不是时限承诺。历史规划轮不授权构建/实施；本次用户明确要求实现roadmap，已授权上述实施、测试和隔离构建验收，不包含外部服务部署。主要成本为 Compose/双端 JVM 测试、正式打包和平台运行，不包含新联网服务、付费模型生成或 Docker 部署。

工具缺失先使用现有仓库工具和平台夹具；无法替代的 macOS、签名、读屏/实体设备验证记录真实阻塞。新增代理/审查轮次/完整测试，或发现必须改协议、数据迁移、显著扩张范围时，先说明失败证据、原因、影响和新增成本，等待用户决定；范围内必要读取与诊断继续。

## 5. 历史规划交付核验

本轮只核对字段、action、状态枚举、控制器恢复与计时链路、共享组件、现有测试/构建入口，以及方案与 roadmap 的相对链接和 Markdown 差异。开发规格 P1–P14、D01–D10 均映射到上面的批次；产品 checkbox 全部保持未勾选。没有运行产品测试、构建或宣布原生 BUG 已修复。
