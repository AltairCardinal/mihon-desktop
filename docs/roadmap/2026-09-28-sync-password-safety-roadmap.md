# 同步空间默认无密码与遗忘风险提示 Roadmap

日期：2026-09-28。状态：产品方案及 HTML DEMO 已获用户批准，开发契约已细化，**生产实施尚未开始**。批准本方案不表示已完成产品验证，也不启动本清单的实施。

设计权威：[同步密码安全设计](../2026-09-28-sync-password-safety-design.md)，尤其第 3–5 节产品交互和第 10–12 节开发/失败/验证契约。审阅基线：`cdc1b9f1b3`；[并列 HTML DEMO](../prototypes/multi-device-sync/password-review.html)与[原型说明](../prototypes/multi-device-sync/README.md)。本文件是产品 child plan，从第一个未勾选项推导进度，不声明 active-task，不切换其他专项父计划的 active-child-plan。

## 1. 目标、复用与范围

用户新建同步空间时默认不设置额外密码，可直接进入初始化和合并；主动设置密码时必须看到持久风险警告并确认。已有加密空间继续验证原密码，并提供准确的遗忘帮助；设置页只读显示真实保护状态。两端语义一致，不把 GitHub 私库访问权限称为客户端加密。

复用现有 SyncPanelContent、SyncPanelController、SyncOnboarding、SyncSetupStorage、SyncSpaceCrypto、平台外壳及集成测试。沿用远端 v2 空间格式、本机 version 3 pending、安全存储、专用空库初始化和失败恢复；新特性追加在现有链路。无须抽出新的密码服务或同步框架。

排除：找回/重置密码、恢复码、托管密钥、修改/关闭既有密码、数据重加密迁移、删除/重建空间、授权权限改造、后台调度和同步进度改版。本 roadmap 不替代 [09-19 roadmap](2026-09-19-sync-least-privilege-onboarding-roadmap.md) 的未完成 M3；可复用确实覆盖同一候选的联调证据，但不得顺带勾选其状态或混入其未授权工作。

实施开始时核对 HEAD、工作区状态与最新源码。当前审阅工作区是 `D:/Codex/worktrees/sync-password-review`；原仓库有其他会话工作，不能直接复制整文件覆盖或混合提交。若复用另一个已具备最新代码的工作区，先对照已批准交互与当前同步进度改动，保留双方行为。发现契约不兼容时先明确差异，不重做已完成底层能力。

## 2. 交付清单与依赖

- [ ] S1：交付默认无密码、主动加密确认、遗忘帮助及设置状态的共享生产闭环，完成双端接线、相关测试和独立审查。
- [ ] S2：完成双端正式候选、恢复与平台运行验收，记录可用产物和剩余环境限制。

严格依赖 S1 → S2。S1 是一个可独立使用的完整功能批次，UI、动作防护、秘密清理及恢复接线不能拆开交付。S2 以已审查实现为输入，不承接新功能。复选框只在该项实现/审查/验证/提交全部完成时勾选；测试绿但审查未过、产物不可用或必要环境缺失时保持未勾选。

### S1：共享生产闭环及双端接线

**前置输入**：已批准设计 P1–P12、开发契约第 10–12 节、现有 Desktop UI 规范及共享组件事实；核对实际源码后冻结验收。所有拟议接口仍待实现，不能以文档描述当作已有代码。

**修改边界**：

- 主实现：`data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanel.kt`、`SyncPanelController.kt`，`presentation-sync/src/commonMain/kotlin/mihon/presentation/sync/SyncPanelContent.kt`，i18n 的 base / zh-rCN 资源。
- 平台：现有 AndroidSyncPanel、DesktopSyncPanel 的必要返回/关闭/焦点适配及对应测试；不新建密码专用 Screen/Tab/DI。若实际必须新增集成点，先说明原因并补真实导航/DI 测试。
- 测试：现有共享 panel/storage、Compose/controller/onboarding 集成、平台外壳及 wiring 测试。底层 crypto/transport 默认不改 production；只对本次接线缺失的组合补断言。
- 同步维护本文执行证据和设计中的实际命名/来源，不能改变已批准语义。不扩大到其他同步页面、仓库权限或恢复协议。

**实施内容**：

1. 引入显式新建动作与临时上下文身份。NONE 必须是空密码且无残留确认；PASSWORD 必须非空、现有校验通过且确认风险。旧 SubmitPassword 限于 UNLOCK。控制器检查页/步骤、当前候选、会话身份和 busy，拒绝矛盾、过期、重复事件；IME 与按钮共用同一动作。
2. 完整接入创建页：标题/默认关闭开关/条件说明、开启后的密码框/显隐/风险卡/确认、两种提交文案。编辑撤销确认，显隐保持选区；关开与离开清理；重绘/主题/尺寸保持当前草稿。使用会话内 TextFieldValue，禁止保存原始密码到状态流、磁盘或日志。
3. 连接提交与既有恢复：派发时捕获选择后清 UI，持久 pending 接管模式与材料；失败重试/重启优先恢复原尝试。尚无 pending 的失败必须重新检查并让用户重新提交，不能自动执行默认无密码。竞争赢家按实际 descriptor 进入已有空间分流，不覆盖。
4. 同一面板内增加帮助页，来源只能是有效 UNLOCK 或已加密设置。清理输入，帮助无网络/连接副作用；返回、Escape、Android Back 一次退一层。来源失效安全退 MAIN，关闭清理来源，返回焦点只请求一次。只读密码状态取真实连接 descriptor，未知不显示未设置。
5. base/中文资源、semantics、密码输入/IME、错误反馈、主题颜色同步落地；适配既有双端外壳，所有新控件都有可达入口与结果反馈。按设计 P10 处理内部滚动、320dp/大字号与 Desktop 键盘；原型 320px 不当作原生 320dp 的证据。

**红绿重构与验收**：

先扩展真实测试夹具，让新增断言因缺少目标行为失败，再最小实现并重构。测试编译/环境失败不算有效红测；不要把生产逻辑复制进测试或只检查源码字符串。

| 测试组 | 本批必须覆盖的判据 |
| --- | --- |
| 创建与真实请求链 | 默认 None；有效 Password + 确认创建成功；输入空/无确认/过长/非法/矛盾动作无写入；旧 SubmitPassword 不能绕过创建；重复提交仅一尝试；实际生成模式/解锁结果与 UI 一致 |
| 草稿与身份 | 修改撤销确认；显隐保留选区；主题重组不清空；关闭/离开/账号候选变化清理；旧 context 与关闭前排队事件不能创建；两个独立会话草稿互不影响 |
| 恢复与安全 | pending 前失败不自动重提；pending 后失败/重启用同一 attempt/material/protection；响应丢失读回；赢家加密/无密码分别分流；旧空间/legacy pending 保持现有规则；不把格式/网络错误当 None |
| 帮助与设置 | 解锁/设置两来源正确；清空旧密码；点击/Back/Escape 回一层；关闭和来源失效正确；帮助期间零初始化/同步/断开；状态取 descriptor，错误状态不谎报未加密 |
| 原生与可访问性 | 两端接入共享实现；开关/确认/禁用/错误 semantics；320dp、200% 字号可滚动到全部操作；Desktop Tab 圈定、还焦、IME 与显隐；双主题可读 |

沿用设计第 12 节的测试归属。MockWebServer 使用真实解析器并记录实际请求；涉及 HTTP 解析变更时还必须覆盖成功、空/缺失、403/429/500、畸形响应，不为新增说明文案重写 HTTP 客户端。若已有底层测试覆盖某个异常，可复用结果，并额外证明 UI 没绕开它。

**Focused 命令示例**（选当前行为执行，不每次全部运行；Windows 当前会话设 UTF-8、Android SDK；命令从实施 worktree 根执行）：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'

python scripts/gradle-coordinator.py run --key password-ui-focused -- .\gradlew.bat :presentation-sync:jvmTest --tests 'mihon.presentation.sync.SyncPanelOnboardingIntegrationTest' --tests 'mihon.presentation.sync.SyncPanelContentTest'
python scripts/gradle-coordinator.py run --key password-storage-focused -- .\gradlew.bat :data:jvmTest --tests 'mihon.data.sync.JvmSyncPanelStorageContractTest'
python scripts/gradle-coordinator.py run --key password-android-contract -- .\gradlew.bat :data:testDebugUnitTest --tests 'mihon.data.sync.AndroidSyncPanelStorageContractTest'
python scripts/gradle-coordinator.py run --key password-desktop-panel -- .\gradlew.bat :app-desktop:jvmTest --tests 'mihon.desktop.sync.DesktopSyncPanelTest' --tests 'mihon.desktop.sync.DesktopSyncWiringTest'
python scripts/gradle-coordinator.py run --key password-android-panel -- .\gradlew.bat :app:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.data.sync.AndroidSyncPanelTest' --tests 'eu.kanade.tachiyomi.data.sync.AndroidSyncRuntimeWiringTest'
```

共享阶段完成时串行执行 `:data:jvmTest :data:testDebugUnitTest :presentation-sync:jvmTest spotlessCheck`，纳入相关 crypto/transport/runtime 契约并记录实际任务、测试数、跳过和失败。平台完整测试与发布留在 S2；focused 结果不能冒充全量，也不因一处文案修订重复所有模块。所有 Gradle 经协调器，等待超时先查原任务，不能另起一份。

**审查与完成门槛**：实施者提交稳定 diff 和结构化回执；未实施目标部分的主代理独立检查真实 UI → controller → onboarding、秘密生命周期、持久恢复/竞争、不覆盖、过期事件和平台返回。审查通过后才进入 S2。安全关键路径以实际测试和代码证据判断，HTML 通过不代替。范围预计跨多于 8 个文件，因共享动作、UI、资源和两端集成组成同一能力而保持一个批次；按实际 diff 记录风险，不压缩格式或人为拆件。

**交付**：一个包含功能、测试、必要文档和本项证据的提交；完成回执含 status、diff、tests、commit、process、next。未提交前不先勾选本项；checkoff 与实现同批提交，不创建“只推进状态”提交。

**停止条件**：需要改加密/存储格式、实际恢复依赖不存在、必须覆盖远端、账号/仓库身份不能可靠核验时，保持安全失败并记录失败测试与最小替代方案，暂停相关扩大工作。普通失败先在本批边界修复，不扩展成新恢复系统。

### S2：双端正式候选与恢复验收

**前置**：S1 已审查、验证并提交，关键接口稳定；本次构建 diff 可追踪，没有混入其他会话修改。核对 Android 正式证书/SDK、Windows 构建工具和 macOS 发布环境；环境不可用只阻塞依赖项，可继续独立验证。

**修改边界**：只接受 S1 功能相关的验收修复、脚本正常产生的版本信息及同批证据；修复仍需有效红绿回归并交原实施者。新增功能或第二轮独立功能审查超出原预算时，先报告原因、范围和成本。

**执行及可操作验收**：

1. 收口测试：Android 完整 `:app:testReleaseUnitTest`、Desktop 完整 `:app-desktop:jvmTest`、项目规定 Test Mode / E2E 和格式检查。经协调器串行执行；Desktop 完整测试优先由正式构建脚本承担，若同 diff 已有等价全量证据才用 build-only，避免重复。必要 macOS 平台验证记录各自环境，不能拿 Windows 通过代替。
2. Android 走 [统一构建与验收规范](../architecture/android-build-and-acceptance.md)：`python scripts/build-android.py check --signing` → `candidate` → `verify --artifact <实际候选>`。使用原证书和正式递增版本，不交付 `app/build/outputs` 中间 APK，不隐式安装或操作用户设备。
3. Windows/macOS 走 `scripts/build-desktop.sh` 正式入口；测试模块按 [Test Guide](../automation/TEST_GUIDE.md) 使用真实发布运行时。Windows 交付日志 `Final unpacked EXE:` 的现存文件，不引用 tmp/build；macOS 同样记录实际应用包及运行证据，不以系统 JDK 或独立 HTTP 客户端替代业务链路。
4. 从“书架 → 同步”开始，用专门测试账号/空间分别验证：新建默认无密码 → 自动合并 → 第二设备授权后直接读回；主动密码 → 警告/确认 → 第二设备错误密码拒绝/正确密码读回。用户数据只用虚构内容；真实写入使用已明确授权的隔离目标，未授权时本地 MockWebServer 证据标记为本地。
5. 两端在“输入密码 → 忘记密码 → 返回”和“设置 → 忘记密码 → 返回”验证返回键/Escape/关闭与输入清理；核验设置状态、帮助无连接副作用。通过真实 UI 操作验证密码修改撤销确认、关开清空、未确认不能提交、主题切换和输入法不绕过门禁。
6. 在获准的隔离环境中验证加密初始化响应丢失/重启恢复、两个客户端争用已有空间及日常交换；断言原空间无覆盖、保护模式不降级。失败注入若仅在本地 production 集成夹具完成，明确证据层级，不假称真实 GitHub 故障已复现；不为测试删除或损坏用户原空间。
7. 完成原生深浅主题、Android 320dp/200% 字号及 Desktop 键盘/焦点/内部滚动验收，记录来源、系统、版本、尺寸/缩放与输入方式。沿用允许的离屏视觉或外部验收方式，不读取系统桌面像素；HTML 截图只作为交互参考。

**交付与证据**：在本文第 4 节记录验收命令/退出码/测试数、实际产物路径与版本、签名/运行状态、P1–P11 对应生产证据及 P12 已有 HTML 证据。必要修复与 checkoff 同批提交；若本项只产生验证和构建版本记录，合并为一次完整验收交付，不拆多份状态提交。面向用户报告可点击正式产物和操作路径。

**完成边界**：真实账号/空间权限、设备安装或 macOS 环境缺失时不得标为全端发布完成；明确已可交付的平台和未完成项。本计划不因其他 roadmap 的历史候选而跳过当前 diff 验证，也不重新申请已在当前执行会话明确授予的同范围授权。

## 3. 执行预算与协调约束

本次文档完善由主代理直接完成，不调用实施代理、不运行产品构建。以下是后续用户启动实施后的预算，不是本次执行记录：

| 项目 | 计划上限与用途 |
| --- | --- |
| 技能/规范 | 先读仓库 Desktop UI 规范及适用组件事实、页面契约；现有能力足够，不因可用技能而增加网站、设计稿或新工具链 |
| 代理 | 1 个实施子代理承担 S1 的主要代码、TDD 和验证；主代理负责契约、独立审查和 S2 集成验收。相同上下文的修复复用原代理，不额外建立只审查代理 |
| 并行 | 实施代理改代码时，主代理可只读核对验收/平台环境；两者不编辑同一文件，不同时运行重型 Gradle。S1/S2 不并行 |
| 审查 | 1 轮稳定批次独立审查，必要时 1 轮修复复审；高风险接口未通过前不发布。复审说明具体未通过项、失败证据、修复范围和新增成本 |
| 测试 | focused 按实际红绿行为运行；共享阶段完整测试 1 轮；最终 Android/Desktop 各 1 轮完整测试，构建能复用的同 diff 证据复用。失败补受影响测试，新增无关全量/审查轮次先说明并确认 |
| 过程产物 | 本 roadmap 是唯一过程计划；最终证据合并记录于本文，不另建逐任务快照或大型 diff 包。设计文档和代码/测试/正式产物是交付物 |
| 时间估算 | S1 约 3–5 小时，S2 约 2–4 小时；不含等候外部授权、不可用设备及首次依赖下载。主要成本是 Compose/共享安全路径测试和两平台正式构建，不是新增依赖服务 |
| 追加条件 | 安全假设失效、测试暴露无关基线失败、构建/签名/平台不可用时先区分事实与推断；可继续无依赖工作。扩大产品范围、增加代理/审查/全量次数、显著额外成本或不可逆操作前说明替代方案并等待决定 |

实施派单必须给出目标、前置文档、允许修改路径、冻结验收、当前 diff/进程与交付格式。主代理不重复实施；代理完成须先返回结构化回执。长任务报告日志/PID，超时先查状态，不重复发起构建。真实网络访问遵循仓库会话代理规则；本地测试绕过代理。

## 4. 当前证据与执行记录

| 阶段 | 已知事实 | 不代表什么 |
| --- | --- | --- |
| 产品审阅 | 用户已通过默认无密码、可选密码风险提示、遗忘帮助及双端 HTML 方案 | 不代表生产已修改，不授权任意远端删除/重建 |
| HTML 基线 | `cdc1b9f1b3`；完整 DEMO 42/42、0 跳过，独立检查及修复见设计第 9 节 | 不代表真实加密、GitHub 服务或原生构建验收 |
| 本次文档补全 | 第 10–12 节明确接口、状态、存储/兼容、失败矩阵与测试映射；本 roadmap 明确两个交付批次。4 份 UTF-8 文档、19 处本地链接/锚点、13 处完整源码路径、6 个 focused 测试类入口、两项未开始任务及 `git diff --check` 核验通过 | 不把静态源码核对记为运行测试；本次未运行 DEMO/原生测试或构建 |
| S1 | 未开始；暂无生产红绿/审查/提交证据 | 不勾选 |
| S2 | 未开始；暂无本次正式产物/运行证据 | 不引用历史产物作为本次交付 |

执行时直接补充本表下的批次证据，保留失败和环境限制；不要为每次命令创建新报告，也不要只改 checkbox 另开提交。
