# 同步空间默认无密码与遗忘风险提示 Roadmap

日期：2026-09-28；生产执行启动：2026-09-30。状态：S1 共享生产闭环、双端接线、必要红绿回归与独立审查已完成，随本批提交交付；S2 正式候选及平台运行验收待执行。完成状态由下列复选框及第 4 节证据共同约束，接口存在不等于已完成发布验收。

设计权威：[同步密码安全设计](../2026-09-28-sync-password-safety-design.md)，尤其第 3–5 节产品交互和第 10–12 节开发/失败/验证契约。审阅基线：`cdc1b9f1b3`；[并列 HTML DEMO](../prototypes/multi-device-sync/password-review.html)与[原型说明](../prototypes/multi-device-sync/README.md)。本文件是产品 child plan，从第一个未勾选项推导进度，不声明 active-task，不切换其他专项父计划的 active-child-plan。

## 1. 目标、复用与范围

用户新建同步空间时默认不设置额外密码，可直接进入初始化和合并；主动设置密码时必须看到持久风险警告并确认。已有加密空间继续验证原密码，并提供准确的遗忘帮助；设置页只读显示真实保护状态。两端语义一致，不把 GitHub 私库访问权限称为客户端加密。

复用现有 SyncPanelContent、SyncPanelController、SyncOnboarding、SyncSetupStorage、SyncSpaceCrypto、平台外壳及集成测试。沿用远端 v2 空间格式、本机 version 3 pending、安全存储、专用空库初始化和失败恢复；新特性追加在现有链路。无须抽出新的密码服务或同步框架。

排除：找回/重置密码、恢复码、托管密钥、修改/关闭既有密码、数据重加密迁移、删除/重建空间、授权权限改造、后台调度和同步进度改版。本 roadmap 不替代 [09-19 roadmap](2026-09-19-sync-least-privilege-onboarding-roadmap.md) 的未完成 M3；可复用确实覆盖同一候选的联调证据，但不得顺带勾选其状态或混入其未授权工作。

实施开始时核对 HEAD、工作区状态与最新源码。当前审阅工作区是 `D:/Codex/worktrees/sync-password-review`；原仓库有其他会话工作，不能直接复制整文件覆盖或混合提交。若复用另一个已具备最新代码的工作区，先对照已批准交互与当前同步进度改动，保留双方行为。发现契约不兼容时先明确差异，不重做已完成底层能力。

## 2. 交付清单与依赖

- [x] S1：交付默认无密码、主动加密确认、遗忘帮助及设置状态的共享生产闭环，完成双端接线、相关测试和独立审查。
- [ ] S2：完成双端正式候选、恢复与平台运行验收，记录可用产物和剩余环境限制。

严格依赖 S1 → S2。S1 是一个可独立使用的完整功能批次，UI、动作防护、秘密清理及恢复接线不能拆开交付。S2 以已审查实现为输入，不承接新功能。复选框只在该项实现/审查/验证/提交全部完成时勾选；测试绿但审查未过、产物不可用或必要环境缺失时保持未勾选。

### S1：共享生产闭环及双端接线

**前置输入**：已批准设计 P1–P12、开发契约第 10–12 节、现有 Desktop UI 规范及共享组件事实；核对实际源码后冻结验收。实施入口最初是拟议接口；实际实现与命名见设计第 10 节，不能以文档描述代替生产行为验证。

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

此前文档完善由主代理直接完成；用户于 2026-09-30 启动生产实施，采用以下预算。实际执行与限制记录于第 4 节：

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
| S1 | 2026-09-30 在 `codex/sync-password-review`、起始 `dfd991567c` 实施；完成共享功能、双端接线、有效红绿与独立审查，并按用户批准追加一次作用域修复的小范围复审；本批提交包含 checkoff、production、测试及必要文档 | 不代表 S2 远端/正式运行验收完成；完整测试首轮失败与 focused 修复证据均保留如下 |
| S2 | Android 完整测试及正式签名候选校验通过；Windows 经批准的完整复验、正式产物及真实发布运行时验收通过；macOS 正式候选已构建，值守复验通过桌面应用启动路径完成安全存储重启验收；SSH 直接启动的失败仍保留。详见下方记录 | 不代表实体设备升级、真实远端双端或 macOS 原生窗口/视觉验收通过 |

执行时直接补充本表下的批次证据，保留失败和环境限制；不要为每次命令创建新报告，也不要只改 checkbox 另开提交。

### S1 执行证据（2026-09-30）

主代理冻结原 P1–P12 契约并读取 Desktop UI 规范；唯一实施代理承担 production、红绿测试和串行 Gradle，主代理独立审查安全/恢复/真实 UI 接线及离屏视觉。原仓库并发未提交文件未被编辑或提交。生产实现复用既有 onboarding、crypto、transport 与 v2/v3 格式；凭据 store 的新增会话标记仅区分正常刷新与显式换授权，不改变持久记录或 CAS 边界。

有效红测包括：旧 SubmitPassword 仍创建、默认新建仍显示密码框、缺少创建上下文/帮助动作、pending 前失败不要求重新提交、关闭残留返回标记、正常刷新误拒创建/显式换账号未失效，以及真实键盘进入帮助后 Escape 无焦点无法返回。测试编译错误、夹具响应缺字段和 Compose 场景初始化异常均单独诊断，不计作产品红测。修复复审聚焦这些有效失败、原始密码闭包引用及时释放与真实键盘判据，没有扩大到新功能。

| 范围 | 有效证据与边界 |
| --- | --- |
| 共享 storage/controller | `JvmSyncPanelStorageContractTest` 45/45 通过；真实 credential refresh CAS、旧 context/换账号/关闭事件、两种保护选择、非法动作零写入、pending 恢复与帮助无副作用。竞争测试同时覆盖 Password 赢家和 None 赢家，不覆盖赢家空间。阶段全量还将运行 Android 同一共享契约。 |
| Compose → production → HTTP | `password-s1-focused-complete` 退出码 0，42 秒：Content 64/64、onboarding integration 5/5。真实 Compose 动作驱动 controller/onboarding/MockWebServer；覆盖默认 None 与主动 Password、原密码解锁、门禁/草稿及帮助。 |
| 平台外壳与键盘 | 同轮 DesktopPanel 1/1；AndroidPanel 9 项中 6 通过、3 既有 Unix FileProvider 限制跳过。Desktop 真实正反 Tab 在 modal owner 内遍历至少四控件、完整循环且互逆，Enter/Space 不触发背景入口、帮助后真实 Escape 退一层、关闭还焦；不以背景独立 focus owner 的保留标志推断泄焦。Android 真实 IME、系统返回及窄屏大字号门禁通过。相关 runtime wiring 前轮 14 项通过。 |
| 原生离屏视觉 P10 | 共享 Compose `syncVisuals=true` 生成深浅主题 320×900、density 1、fontScale 2 的风险/帮助图及 560×720、fontScale 1 的 Desktop 风险图，位于 `presentation-sync/build/sync-visual/`。主代理查看主题语义色、图标和正文；滚动/确认/提交/关闭/返回可达由真实事件断言补足。来源是 Windows JVM 上的共享 Compose 测试，未读取系统桌面像素，不代表实体 Android 或正式应用截图。 |
| 静态/范围 | `password-s1-format-complete` 退出码 0，28 秒；`git diff --check` 通过。超过 8 文件/400 行仍属同一个创建/帮助能力，风险集中于错误降级、身份与秘密生命周期、恢复、焦点；不作无关重构。 |

红绿及 focused 命令/退出码由本工作区 `.gradle-coordinator/password-s1-*.json` 与同名日志保存；这些是忽略的过程产物，不进入应用交付或仓库。完整阶段结果与修复证据如下；S1 checkoff 与 production、测试和必要文档同批提交。

阶段首轮 `password-s1-stage`：`:data:jvmTest :data:testDebugUnitTest :presentation-sync:jvmTest spotlessCheck --max-workers=2`，退出码 1，16 分 5 秒。data JVM 762 项中 760 通过、1 失败、1 跳过；其余任务因前序失败未执行，不记为通过。失败在 `SyncAuthorizationSafetyContractTest` 启动前收到 `UncaughtExceptionsBeforeTest`，suppressed 为 `CountPendingImportsQuery` 的数据库已关闭异常；不是该用例业务断言失败。跳过项为既有 `SyncGitCompareAcceptanceTest` prepared-artifacts replay。

实施者进一步用 JVM 字节码核验：controller 初始化中的七个 `scope.launch` 使用构造参数 scope，而 `stop()` 只取消/等待私有 lifetime；这些观察协程可能在数据库关闭后仍执行。此问题同时影响原有数据库观察者与本次授权标记观察者，不能当作无关基线忽略。必要修复限定为显式面板作用域及确定性 stop 回归；不改同步协议或数据库格式。已用完原声明的一轮修复复审，额外安全 production 修复的独立复审需用户批准，未获批准前不提交功能或发布。原轮未执行模块与受影响 focused 可继续；不重跑整个 data 全量冒充首轮成功。

用户随后明确批准一次仅针对 controller 作用域修复的新增独立复审（预计增加 10–20 分钟，无新增代理/全量轮次）。`password-s1-stop-red` 退出码 1：确定性共享测试在 stop 后发现 6 个观察者残留，调用方 parent 仍 active，finally 等待清理避免跨用例污染；`password-s1-stop-green` 退出码 0、53 秒：storage 46/46 与 AuthorizationSafety 5/5，无跳过，XML 不再记录闭库异常。修复只把私有作用域命名为 `panelScope` 并显式用于全部 launch，构造参数和公共接口不变。主代理独立核对源码、stop 的 cancelAndJoin 顺序及真实生产测试，另执行 javap 确认七个初始化 receiver 均为 `getfield panelScope`；此新增小范围复审通过，无新增阻塞。首轮其他 data JVM 成功证据保留，不把 focused 补验表述为第二轮完整 data JVM 全绿。

`password-s1-stage-remaining` 退出码 0、2 分 9 秒：`:data:testDebugUnitTest :presentation-sync:jvmTest spotlessCheck --continue --max-workers=2`，无 init/测试过滤、不重复 data JVM 全量。Android data 354/354、Compose 69/69，0 失败、0 跳过；项目 `spotlessCheck` 通过。这补齐首轮未执行任务，作用域修复同时由两平台共享 storage 契约及真实 Compose/controller/onboarding 集成验证。两份文档 UTF-8、本地 13 链接与 `git diff --check` 检查通过。最终约 20 个文件仍是同一功能批次，包含测试专用桥接、资源、两端 shell、必要生命周期修复与文档，没有新增生产依赖、Screen、导航或持久格式。

S1 的三个审查节点：一次批次独立审查、原预算内的一次修复复审、用户明确批准追加的一次仅 controller 作用域复审。未新增代理；所有重型 Gradle 串行由同一实施者协调。S1 在此提交中交付，S2 保持未勾选，正式产物/运行与真实远端双端验收不得复用 HTML 或本地 Mock 代替。


### S2 执行证据（2026-09-30）

S1 提交为 `412248cdae21c5728bda13a50b29eec7bc6181b7`。S2 只推进本次正式候选、版本与证据；不安装 Android 应用，不操作实体设备或未经授权的真实 GitHub 账号/空间。

| 范围 | 实际结果与限制 |
| --- | --- |
| Android 完整测试与 E2E 客户端 | `password-s2-android-full` 执行 `:app:testReleaseUnitTest :test-desktop:test spotlessCheck -Pmihon.testBuildType=release --continue --max-workers=2`，退出码 0，16 分 51 秒。app 673 项、0 失败、7 跳过；test-desktop 52/52；格式通过。跳过为 3 项既有 Unix FileProvider 限制及 4 项 Release JVM 无 Android SQLite native driver 的迁移测试，不计为运行通过。 |
| Android 正式候选 | `check --signing`、`candidate`、`verify --artifact` 均退出码 0；candidate 2 分 42 秒。版本 `0.19.4-aex.18` / versionCode 36，沿用正式身份与原证书，R8/资源收缩启用、telemetry/updater 禁用，v2/v3 签名校验通过。冻结 production 输入来自 S1 加正常 Android 版本递增；未安装，候选校验不代表运行或升级验收。 |
| Windows 首轮完整测试 | 官方 `scripts/build-desktop.sh` 经 `password-s2-windows-build` 串行协调，启用本地 integration、max-workers 2；Desktop 3237 项、2 失败、3 跳过，退出码 1，6 分 10 秒。脚本在构建之前停止，没有可交付 EXE。首轮正常版本分配 BUILD 67→68，批准重试再分配为 69，STAGE 11 / FEATURE 19 不变。 |
| Windows 失败与 focused 补验 | `DesktopTestProfileTest` 的真实 main 隔离子进程在 30 秒内未结束；`DesktopSourcesScreenModelTest` 启动前收到 `UncaughtExceptionsBeforeTest`，suppressed 为作者归档查询 `stmt pointer is closed`。首轮作者 wiring 日志包含相同取消协程；尚未证实具体用例或因果，不宣称无关基线或本次密码功能导致。`password-s2-desktop-diagnostic` 对 profile（8）、AuthorsProductionWiring（22）、sources（3）共 33/33、0 跳过，46 秒、退出码 0；没有修改源码或出现闭库诊断。focused 使用忽略目录中的临时 init，仅在失败时保留 JUnit 临时目录。该结果不冒充第二次完整套件通过。用户随后批准一次完整复验及后续构建（增加 10–20 分钟，不增加代理/审查；若再失败则保留阻塞、不扩大修复范围），`password-s2-windows-retry` 完整复验与后续构建通过，结果如下。 |
| macOS 隔离环境与 focused | `mbp-lan`，macOS 14.8.4 / JDK 21.0.10+7；在 `/private/tmp/mihon-password-s2-412248c` 检出 S1，不改日常仓库、应用或钥匙串设置。`password-s2-mac-focused` 离线、max-workers 1，Panel/Wiring/SecureStore 共 7 项、1 失败，2 分 30 秒：真实 OS secure store roundtrip 报安全存储不可用，其余 6 项通过。只读 `SecKeychainCopyDefault`/`GetStatus` 返回成功、statusBits 2；本机 SDK 常量为 unlock 1/read 2/write 4，即未报告解锁/写入状态。该观察不单独证明测试失败原因。需用户自行解锁后才能补验，未索取密码或改变设置；正式 macOS 构建未开始。 |

Android 正式产物为 [Mihon Fork 0.19.4-aex.18 APK](../../app/artifacts/android/0.19.4-aex.18-vc36-412248cdae-release/Mihon-Fork-0.19.4-aex.18-vc36-release-universal.apk)，SHA-256 `d5f2f8f29712bf1fd63e79b1cfaad8d8faf3174e2617b8248726c4c13df5aa7d`。完整候选目录保存 mapping、签名/产物元数据；正式产物是忽略的本地产物，不写入 Git。

本次真实 GitHub 双端、实体 Android 的运行/升级及 macOS 完整运行验收尚未完成，S2 复选框保持未勾选。S1 原生离屏 UI/真实事件、production controller 与本地 HTTP 恢复/竞争证据仍适用，但不代替上述运行层级。


用户回复 macOS 已解锁后，`password-s2-mac-unlocked` 仅补跑 `DesktopSyncSecureStoreTest`，15 秒、退出码 1，4 项中 1 失败，其余 3 项通过；真实 OS roundtrip 仍报不可用。再次只读核对 default keychain 为 `login.keychain-db`、statusBits 仍 2。该状态与用户操作不一致，原因未证实，不擅自变更钥匙串或把其余平台测试通过记为 macOS 可发布。macOS 随后生成隔离正式候选，以应用包内程序核对生产链路；安全存储运行验收仍失败，结果如下。


用户批准的 Windows 追加完整复验与后续构建：`password-s2-windows-retry` 调用官方 `scripts/build-desktop.sh`，协调器退出码 0，总计 7 分 8 秒。Desktop 完整 3237 项、0 失败、0 error、3 跳过，4 分 52 秒；跳过分别为 macOS JXA 专项、Windows 原生窗口 affinity 环境项及明确 non-release 的 library 配置项。首轮两项失败本轮未复现，未修改作者或 profile/sources 源码，故不宣称已修复它们或已证实首轮原因。正式打包 2 分 2 秒、退出码 0，脚本验证真实未打包应用版本与 production 扩展 APK 安装通过；随后发布正式目录与 ZIP。

| 正式候选/运行 | 实际产物及证据 |
| --- | --- |
| Windows `0.11.19.69.412248c` | 日志 `Final unpacked EXE:` 的文件为 [Mihon Desktop.exe](../../app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.69.412248c-unpacked/Mihon%20Desktop.exe)，已核实存在。[Windows ZIP](../../app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.69.412248c-windows.zip) SHA-256 `a75d969d21d8aac4ba152f5e0c9fe86a004e071b8917b2c64e9c9c4cf6666103`。 |
| Windows 真实 Test Mode | 使用上述正式 EXE，在新建隔离 profile 两次启动；真实 production controller 打开/进入设置 SIGN_IN/关闭、OS-backed store 写入/读回、重启后读回并移除保留前缀的虚构 probe 记录均通过。进程 4048/50172 均经 `/test/shutdown` 正常退出码 0，验收脚本退出码 0。没有授权 GitHub、真实同步、读取屏幕像素或触碰日常 profile。 |
| macOS `0.11.19.68.412248c`，未验收候选 | 官方 `scripts/build-desktop.sh build-only` 经 remote `password-s2-mac-build` 串行协调，退出码 0、34 秒；复用 Windows 相同 Desktop 功能代码的完整测试证据，remote 仅正常递增 BUILD 67→68，Android 版本差异不进入 Desktop 生产实现。最终 app 为 `/private/tmp/mihon-password-s2-deploy-412248c/Mihon Desktop.app`，完全独立于日常 `/Applications` 应用。保留 [macOS 未验收候选 ZIP](../../app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.68.412248c-macos-unverified.zip)，SHA-256 `67d87adaa1b7283fd8cf775b832c6fd41b620bb3e595ddf5513d05583e4cff45`，仅供核查，不能称为可发布。 |
| macOS 真实 Test Mode 阻塞 | 使用最终 app 内 `Contents/MacOS/Mihon Desktop`，新建隔离 profile；health、production controller 打开/设置 SIGN_IN/关闭通过。真实 `/test/sync/probe/write` 返回 503，stage `WRITE` / failureType `SyncSecureStoreException`；未进入重启读回，验收脚本退出码 1。应用经 shutdown 正常退出码 0。这是正式发布运行链路的实际失败；未把系统 JDK 测试或单独 security 命令成功当作产品验收。未改变 OS 后端、解锁机制或安全降级路径，后续需要单独定位默认钥匙串与 SSH 会话访问状态。 |

S2 正式构建及 runtime 过程日志仍在忽略的 `.gradle-coordinator/` 与 macOS 隔离工作区；没有另建过程报告。签名 Android 候选与 Windows 正式产物可供用户按“书架 → 同步”手动验收；实体设备安装/升级、获授权隔离 GitHub 空间的真实双端创建/解锁/恢复与 macOS 安全存储仍为未完成门槛，S2 保持未勾选。P1–P11 的共享与两平台本地 production/Compose 行为证据见 S1；P12 沿用已有 HTML 基线，不重跑或改演示资产。

最终 `password-s2-final-format`：`spotlessCheck --max-workers=2` 退出码 0、34 秒；版本分配后的全部格式检查通过。roadmap UTF-8 与 10 处本地链接、三个正式/未验收候选归档的 SHA-256、日志 Final unpacked EXE 的实际文件存在性及 `git diff --check` 均核验通过。S2 验收提交只包含 Android/Desktop 正常版本信息与本文证据，未混入其他用户改动或新增生产修复。


### macOS 值守复验与启动流程（2026-09-30）

用户要求再次尝试 Mac 并现场值守。本轮主代理直接执行，0 新代理、0 全量测试、0 生产代码修改；预计预算 5–15 分钟。隔离候选、既有正式 runtime 验收脚本及无旧 Mihon 进程先行核验。SSH 直接启动同一应用仍在 `/test/sync/probe/write` 的 WRITE 阶段返回 503 / `SyncSecureStoreException`，脚本退出码 1；该失败如实保留。

随后仅把启动方式改为 `open -n -W -a <同一正式 app> --args ...`，经 macOS 应用启动服务进入桌面应用启动路径。使用新的隔离 profile、空闲动态 HTTP/JMX 端口，仍用同一 production controller 与 OS-backed store 接口。phase 1 打开/设置 SIGN_IN/关闭、写入并读回虚构保留前缀 probe；phase 2 同 profile 重启、读回并删除该 probe，全部通过。验收脚本退出码 0；两个 open 包装器 PID 为 27518/27532、均退出码 0，随后核对没有遗留 Mihon Desktop 应用进程。包装器 PID 不冒充应用 PID。没有索取密码、更改钥匙串设置或降级存储后端。

可证实的是：同一候选在桌面应用启动路径下完成真实安全存储跨进程验收，SSH 直接启动路径仍失败。启动上下文是关键差异；具体 macOS 钥匙串会话机制尚未独立定位，不宣称系统缺陷或生产代码已修复。此前 SSH JVM focused 的失败不改写为通过，也不重新运行全量来掩盖失败。将该流程、隔离/关停及证据边界补入 [Test Guide](../automation/TEST_GUIDE.md#macos-桌面会话的安全存储验收)，指导后续正式 macOS 验收。

实际 app 仍为 `/private/tmp/mihon-password-s2-deploy-412248c/Mihon Desktop.app`，CFBundleName 为 Mihon Desktop，CFBundleShortVersionString/CFBundleVersion 均为 `11.19.68`；完整版本 `0.11.19.68.412248c` 来自原正式构建日志。本地 [macOS 候选 ZIP](../../app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.68.412248c-macos.zip) 与之前保留的 unverified ZIP 内容一致，SHA-256 `67d87adaa1b7283fd8cf775b832c6fd41b620bb3e595ddf5513d05583e4cff45`；仅补充新运行证据，没有重打包、重编译或重新分配版本。

macOS 的 production controller 与系统安全存储运行门槛在桌面应用启动路径下已通过；headless Test Mode 不代表原生窗口/键盘/视觉或真实 GitHub 同步。实体 Android 运行/升级与获授权的真实远端双端验收仍未完成，S2 保持未勾选。本轮交付同时包含可复用的 macOS 启动/验收流程说明，不仅推进状态。

另外启动同一正式候选的真实窗口（去掉 headless），以新隔离 profile 打开同步面板，production state 确认 loaded/visible 为 true、page MAIN、connected false；未发起 GitHub 授权。已向值守用户请求窗口/Tab/Escape/入口重开检查，收口时尚未收到反馈，故这四类原生窗口事件不记为通过。随后仅对本次实例调用 `/test/shutdown`，返回 202，并核对无遗留 Mihon Desktop 应用进程。两份文档 UTF-8、本地链接、候选 ZIP SHA-256 与 `git diff --check` 通过；本轮没有 Gradle 或重复构建。
