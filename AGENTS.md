# AGENTS.md

本文件说明 Codex 在本仓库工作时必须遵守的规则。

## 语言

所有面向用户的交流使用中文。确认 bug 已修复时，必须用中文明确说明。

## TDD 强制要求

**所有改变产品行为的功能变化（新增、修改、修复）必须严格执行红绿重构流程。**

1. **红**：先写失败测试，并确认它因正确原因失败。
2. **绿**：写最小实现让当前 focused 测试范围全部通过。
3. **重构**：清理代码，再次确认同一 focused 测试范围全部通过。

**没有对应测试的功能代码不允许提交。**

纯文档、纯文案或不改变产品行为的机械配置调整可以直接执行。

---

## 工程治理与进度状态

- 按能够独立交付和审查的功能批次执行，不按文件、测试类或机械行数切成微任务。
- `Estimated scope` 只是审查提示。超过 8 个文件或 400 行时记录内聚性与风险说明即可；不得为满足估算值压缩格式、复制实现或拆开不可独立编译/验收的上下文。
- 父 roadmap 只保存宏观阶段与唯一 `active-child-plan`；当前执行计划保存唯一 `active-task`；产品 child plan 从第一个未勾选项推导进度，不再声明 `active-task`。
- `parity-manifest.json` 是 capability 状态与证据的机器权威；tracker/report 只保存说明或生成视图，不得反向覆盖 manifest。
- checkbox 表示本批承诺的实现、按风险需要的审查、验证和提交均已完成；低风险可由实施者自查。它不表示整个 roadmap 已具备发布条件。发布就绪与剩余平台缺口另在当前 roadmap 或 evidence 中用说明记录，不建立新的状态服务。
- 一个功能批次原则上只产生一个包含测试、production 与必要 checkoff 的提交；审查修复最多增加一个提交。不得为 close、advance、record evidence 等纯状态推进单独提交。
- 审查按风险决定：低风险变更可由实施者自查；数据迁移、安全边界、发布身份、共享协议或公共运行机制等实质高风险部分，由未实施该部分的人独立审查。独立用户能力本身不触发独立审查。仅在架构假设失效、数据/格式迁移、安全边界或独立用户能力出现时重规划；不得仅因行数增长或格式化结果重规划。

### 分层验证

- 红绿循环：仅运行当前行为的 focused tests；重构后复验同一范围。
- 任务／功能批次完成：运行受影响的单元、集成、wiring 与格式检查。
- 阶段完成：运行该阶段受影响范围的回归测试，不自动运行模块完整测试或全量测试。
- 没有 roadmap 的独立修复：以对应回归测试、受影响的集成测试及格式检查收口，不因修复完成、提交或结束会话而自动运行完整模块测试或全量测试；若有具体未覆盖风险，可按下文申请最小范围扩验。
- 默认按日常体验交付：对本次变更选择最小充分验证，覆盖真实 production 行为、接线和受影响平台的一条用户路径。roadmap、阶段、提交、交接或会话结束本身都不自动触发完整模块测试、全量测试、`finalParityAudit` 或正式发布构建。
- 只有用户明确指定稳定发布/完整验收里程碑后，才按该里程碑计划执行一次完整发布矩阵、Test Mode、正式构建和适用平台运行验收。日常流程仍须当次完成行为 TDD、真实集成接线，以及数据、安全、协议、发布身份等高风险专项验证；显式发布里程碑不豁免这些门槛。

完整模块测试是指未限定测试类或用例的模块测试，例如未使用
`--tests` 限定范围的 `:app-desktop:jvmTest`。
全量测试包括 roadmap 最终验证矩阵要求的完整模块测试组合。
命令是否经构建脚本或协调器包装，不改变其验证范围。

单个任务、功能批次或阶段完成，以及 roadmap 实施项全部勾选、
会话结束、交接或提交，均不自动触发完整模块测试、全量测试、
`finalParityAudit` 或正式发布构建，也不构成用户明确指定的发布里程碑。
不得以拆分会话、将任务称为“迭代”或改用构建脚本绕过本规则。

完整模块测试可用于具体风险所需的最小模块范围，不等同全量测试或完整发布矩阵。若证据表明 focused 范围不足，先说明未覆盖风险、为何较小范围不够、拟执行的最小模块及成本，取得用户明确批准后再执行；可用该模块范围收口时不得扩大到仓库全量。完整发布矩阵仍只在用户明确指定的发布里程碑中执行。

已有 roadmap 如安排了未被用户明确选择的完整发布矩阵，执行前应指出冲突并按本节调整。用户明确选择里程碑后，完整矩阵也只执行计划所需的一次；额度是上限，不是必做次数。重复完整验证须有新的具体风险证据和用户明确批准。

### 常见变更的验证范围

| 变更 | 日常体验的最小充分验证 | 需要当次增加的覆盖 |
|---|---|---|
| 文案、说明或局部视觉 | 目标文案/页面与 `git diff --check`；纯文档不跑产品测试 | 共享样式、布局或主题机制变化时覆盖受影响组件和实际尺寸/主题 |
| 给已有功能增加按钮或入口 | 真实点击 → 既有动作 → 用户反馈；导航或 DI 改动补真实接线测试，并在受影响平台走通该入口 | 新状态、危险/批量动作、公共接线变化时补失败路径、确认/反馈及相关集成覆盖 |
| 数据迁移、同步、凭据或删除 | 针对真实旧数据/持久化的行为测试、失败恢复和身份边界验证；本次使用前完成必要独立审查 | 按具体迁移风险覆盖旧版、平台、回滚与发布 runtime；不得将关键正确性留待定型后补 |
| 明确里程碑中的首次完整测试失败及局部修复 | 保留完整首次结果，分类失败；修复后运行相关回归、集成和格式检查，复用未受影响的有效证据 | 只有 focused 覆盖不了具体剩余风险时才提出最小扩大验证；相关失败关闭且影响范围清楚即可收口，不因“修过代码”重复申请全量 |

### 修复收口与扩大验证门槛

- 测试范围按行为、依赖及风险确定，不按修改行数确定。默认选择覆盖真实 production 行为及集成点的最小充分范围；小改动涉及公共基础设施时仍须评估传播范围。
- 全量测试失败后保留完整结果，汇总失败项并判断关联。先完成已知阻塞的诊断、修复和相关验证，再统一评估剩余风险；不得每修复一项就运行或申请一次全量测试。必要的 focused 红绿循环不消耗全量次数，显著增加成本时仍遵守流程预算。
- 未受后续改动影响的通过结果可以复用。记录基线代码版本及未提交差异、测试命令和结果、后续改动及影响范围、补充测试结果；不得将组合证据描述为“最终代码重新通过了全量测试”。
- 已执行计划要求的首次完整测试后，其失败项可以通过修复及相关补验关闭。相关回归、集成测试通过，且没有未解释的相关失败或影响范围疑点时，结束修复验证；无关失败如实报告，不自动扩大修复范围，也不得宣称全部测试通过。尚未完成执行的测试范围不能视为已有通过证据。
- 完整发布矩阵仅在用户明确指定发布里程碑时触发。里程碑中的完整/全量重跑，以及日常流程中任何超出 focused 的扩大验证，都必须说明具体变化、未覆盖风险、较小范围为何不足、拟执行的最小范围及成本；模块级验证足够时不得扩大至仓库全量。日常扩大验证须经用户明确批准。
- 完整测试已执行且基线有效时，基线加相关局部补验证据可以支持收口；清楚记录基线、后续差异、影响范围和补验结果，不称“最终代码重新通过了全量测试”。
- “代码又改了”“上次全量失败”“为了保险”、提交或会话结束，均不能单独作为扩大验证申请理由。一次批准不授权以后每次修复重复执行；无新风险证据时不得反复提出同一扩大验证申请。

### 子代理完成回执与等待

- 子代理完成实现或审查时，先发送结构化回执，再结束任务。回执包含 `status`、`diff`、`tests`、`commit`、`process` 和 `next`；可用 `python scripts/agent-handoff.py` 验证。
- 长时间工具调用前报告命令、预计时间和可用的 PID/日志位置。
- 等待超时后先检查代理状态和已报告进程。进程仍运行时继续等待，不重复执行命令。
- 代理空闲但没有回执时，只发送一次“返回完成摘要”的 follow-up；连续两次确认空闲且仍无回执后才允许中断。
- 恢复代理时传递现有 diff、测试结果和进程状态，不重新探索或重新实现已经完成的工作。

### Gradle 生命周期

- 同一 worktree 的重型 Gradle 验证由一个协调者串行执行。一次性等待使用 `python scripts/gradle-coordinator.py run --key <name> -- <gradle command>`；需分离启动时使用 `start`，再用 `wait/status` 查询。
- 外层等待超时不代表 Gradle 结束；先查询协调器状态，仍为 `STARTING/RUNNING` 时不得启动第二个 Gradle。
- 需要终止时只停止协调器记录的进程树，不使用全局 Java/Gradle 进程清理。

### 本机 Android SDK

- 本机 Android SDK 固定安装在 `D:\Android\Sdk`。截至 2026-08-29，已安装 command-line tools 22.0、`platforms;android-36`、`build-tools;36.0.0` 与 `platform-tools`，满足当前 `compileSdk = 36` 的 Android 编译和 JVM 单元测试要求。
- 用户级 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 均指向 `D:\Android\Sdk`；用户 PATH 包含 `D:\Android\Sdk\platform-tools` 和 `D:\Android\Sdk\cmdline-tools\latest\bin`。已经打开的 PowerShell/Codex 进程不会自动刷新用户环境，必要时在当前会话显式设置：

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
```

- 仓库忽略的 `local.properties` 使用 `sdk.dir=D\:\\Android\\Sdk`，不得提交该机器专属文件。Gradle 应优先通过该文件发现 SDK；删除或迁移 SDK 时必须同步更新用户环境和 `local.properties`。
- command-line tools 必须从 Android 官方下载页取得并校验官方 SHA-256。本次安装包为 `commandlinetools-win-15859902_latest.zip`，校验值为 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`。下载和 `sdkmanager` 访问境外网络时遵循仓库代理规则。
- 当前 `sdkmanager` 22.0 会提示迁移到新的 Android CLI，但仍能完成 SDK package 安装。`android.exe` 首次运行会额外下载 CLI；除非任务明确需要，不要把这个下载加入普通 Gradle 验证流程。
- SDK 自检以真实文件和 Gradle Android task 为准：至少确认 `platforms\android-36\android.jar`、`build-tools\36.0.0\aapt2.exe`、`platform-tools\adb.exe` 存在，再运行受影响的 Android focused tests；历史 AVD 配置不能代替 SDK 安装证据。

---


## 功能规划原则

**规划任何用户可见 capability 时都必须同时考虑用户界面。**

### Desktop UI 规范入口

- 新增或修改 Desktop 页面、导航、弹层、列表交互，或对应 HTML 审阅原型时，先读 [Desktop UI 实施规范](docs/design/mihon-desktop-ui/README.md)，再读适用的组件事实表和页面契约；其他页面按同样字段定义本次契约，不机械套用三个样本的业务。
- 实现前固定验收，区分 `SOURCE`、`PROJECT_POLICY` 与 `HTML_ADAPTER`。平台常量按实际消费组件核对，不把 Android 值自动作为 Desktop 值；当前 Desktop 封面 7:10 与 Android Book 2:3 即为已知差异。
- 必做验收不得在实现后改为未实现或不适用以获得通过。行为测试执行真实事件及 wiring，视觉检查记录来源和环境；浏览器原型通过不能代替下文要求的原生测试及正式构建验收。

每项用户可见 capability 必须有入口和反馈；内部基础设施不要求独立 UI，但必须被真实产品链路使用并有集成测试。规划时检查：

1. 用户如何触发？（按钮、菜单、快捷键等）
2. 结果如何反馈？（状态、Toast、对话框等）
3. 危险操作是否需要确认？（AlertDialog）

**用户可见 capability 没有入口或反馈 = 功能未完成；内部基础设施没有 production wiring 或集成测试 = 功能未完成。**

### 复用优先

新增功能前必须检查项目内是否已有相同或相近能力可复用，包括：

- 已有 Use Case、Manager、Repository、Service
- 已有搜索、分页、错误处理、缓存、同步、下载、解析流程
- 已有 Screen、Tab、Composable、导航入口
- 已有数据模型、数据库表、查询、状态管理、测试工具

规划时必须回答：

1. 能否直接复用现有功能？
2. 不能直接复用时，是否应抽取公共能力供新旧功能共用？
3. 新特性应追加到已有链路，还是确实需要独立维护？
4. 若独立实现，必须说明不能复用的技术原因和用户体验原因。

**能复用却另起一套实现，默认不允许。**

## 上游对齐原则

Mihon Desktop 源自 Android Mihon。除平台 API 或技术栈差异确实无法复用外，功能语义、数据模型、状态转换、错误处理和持久化行为应与 Android Mihon 保持一致，不得仅因实现更省事而保留 Desktop 独立重写。

确需平台独立实现时，必须说明不可复用的技术原因，并将差异限制在平台 adapter 内。上游对齐不得删除、降级或改变 Desktop 独有功能；共享逻辑与独有能力冲突时，应抽取共享核心并通过平台扩展保留独有行为。

## 有效验证原则

测试必须执行真实 production 实现及其 wiring。不得使用仅扫描源码文本、检查符号字符串存在或在测试中复制实现逻辑的方式代替行为验证。

Android 与 Desktop 预期一致的行为必须使用共享契约测试覆盖；平台特有行为应使用独立集成测试覆盖。如果 production wiring 损坏后测试仍能通过，则该测试不能作为完成证据。

## 完成报告格式

每个面向用户的 change 或迭代最终完成后必须按以下结构汇报，不得省略：

```markdown
## 【功能特性】
- [功能名称]：用户能看到/使用的变化，说明操作路径和边界
  - 示例：下载队列 → 顶部显示 Pause/Resume FAB 按钮 → 点击暂停/继续所有下载

## 【BUG 修复】
- [bug 描述]：修复前现象 → 修复后行为
  - 示例：下载队列管理按钮不可见 → 按钮已改为 FAB，始终可见

## 【验收清单】
面向用户的验收项使用以下格式：
- [ ] 操作路径 → 预期结果
```

规则：

- 每项都必须描述用户实际可见或可操作的变化，不写纯代码细节作为主要内容。
- 验收清单必须可执行；可在几分钟内手动完成的行为给出操作路径，其余行为给出自动化验证命令或运行时证据。若验收生成了构建产物，必须在对应验收项中以 Markdown 超链接给出可点击的绝对本地路径；不得只写纯文本路径。
- Windows Desktop 构建完成后，完成报告必须引用本轮构建日志 `Final unpacked EXE:` 输出的实际绝对路径并先确认文件存在，例如正式构建的 `[Mihon Desktop.exe](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-unpacked/Mihon%20Desktop.exe)`；体验包位于 `app-desktop/artifacts/preview/windows/`，链接须指向本轮真实预览产物。不得把 `app-desktop/tmp/`、Gradle `build/` 或其他临时目录中的 EXE 写成交付地址。
- 必须说明功能边界，例如“仅 QUEUED 状态可取消，DOWNLOADING 不可取消”。
- 内部重构无需虚构新增 UI；应报告它保护的既有用户行为、production wiring、自动化验证证据和当前功能边界。
- 拆分的 Task 之间只记录任务状态和验证证据；完整的用户可见完成报告在 change 或迭代最终完成时统一输出。

## 桌面端构建与部署

只有用户明确指定稳定发布/完整验收里程碑并进入最终收口后，
必须使用项目构建脚本完成桌面端正式构建与运行验收，
**不得直接调用 Gradle 构建部署**。

roadmap 实施任务全部完成、单个 Task/批次/阶段完成，以及会话结束、交接或提交，
均不自动触发完整测试、正式构建、版本递增或产物发布。

普通构建入口包含的完整测试计入最终全量验证预算。
执行前须核对脚本实际执行范围，不能以“构建验收”为由提前运行完整测试。

```bash
./scripts/build-desktop.sh           # BUILD +1，构建并验收未打包应用
./scripts/build-desktop.sh feature   # FEATURE +1，BUILD 重置为 1
./scripts/build-desktop.sh stage     # STAGE +1，FEATURE 重置为 0，BUILD 重置为 1
./scripts/build-desktop.sh msi       # 显式生成 MSI，最后重新生成并验收未打包应用
./scripts/build-desktop.sh build-only # 完整测试基线及必要的局部补验证据有效时，仅构建与运行验收
./scripts/build-desktop.sh preview    # 日常体验包：不跑测试、不分配正式版本、使用隔离产物目录
```

日常体验使用 `preview`：复用 production 打包链，但不运行产品测试、不修改 AppVersion，也不覆盖正式或既有体验产物。Windows 和 macOS 分别写入 `app-desktop/artifacts/preview/<platform>/<id>/`，附带 `preview-manifest.json` 记录源码指纹、实际产物和分层状态。Windows 的 production runtime PASS 仅代表既有扩展运行时检查；Windows 原生交互仍为 `NOT_RUN`。macOS 仅确认应用包构建，production runtime 与原生交互均为 `NOT_RUN`，需单独用 LaunchServices 和外部原生工具验收。体验包生成不等于发布矩阵或发布就绪。

`build-only` 仅用于用户明确指定的最终发布收口，避免重复执行已有有效证据的完整 Desktop JVM 测试。
有效证据可以是当前代码的完整测试通过结果，也可以是已经完整执行的测试基线，
加上后续局部修复的相关回归、集成测试及影响范围说明；基线中的相关失败须全部关闭，
未执行范围不得视为通过。组合证据按“修复收口与扩大验证门槛”记录，不冒充最终代码全量通过。
涉及依赖、构建配置、公共运行机制等变化时，须重新判断基线适用性；
不能证明适用时，先确定缺失的最小验证范围，不自动重跑或申请全量。

该模式不得省略任务／批次要求的相关验证，不免除计划要求的首次完整测试，
也不得绕过另有明确要求最终代码重新全量通过的发布门禁。
该模式仍须完成版本分配、正式产物构建、production runtime 验收与最终产物发布。

正式 Windows 构建在运行验收后，会把完整未打包应用发布到
`app-desktop/artifacts/windows/Mihon-Desktop-<完整版本>-unpacked/`；`preview` 会发布到独立的
`app-desktop/artifacts/preview/windows/<id>/`。本轮构建日志中的 `Final unpacked EXE:` 是对应报告应链接的实际未打包 EXE；`app-desktop/tmp/` 仅为内部构建和 Test Mode 输入。

## 常用命令

Android 构建身份、签名、产物及验收遵循 [Android 构建与验收规范](docs/architecture/android-build-and-acceptance.md)。正式身份和升级版本唯一来源是 `gradle/android-release.properties`；Release 默认属于本 fork，Debug 使用独立 `.dev` 身份。统一入口的验证进度见[实施记录](docs/evidence/android-build-workflow-2026-09-28.md)。

用户交付走 `scripts/build-android.py candidate`，不能直接把 `app/build/outputs` 中的 APK 当成正式交付。构建不隐式安装，安装不隐式操作应用；用户自行验收时不代操作实体设备。不同正式候选递增 versionCode，普通 Debug/查询/测试不递增。原证书必须连续，凭据不可用时不创建替代密钥。

Android 验收优先使用已授权、可隔离的模拟器。模拟器能够执行的真实 production、原生 UI/输入法、持久化、升级及集成验收，应继续执行，不得仅因没有实体设备或等待真机而停止。执行前核对 AVD、serial、正式产物版本、签名和应用身份，使用虚构夹具，不操作其他模拟器或用户实例。只有依赖实体硬件、厂商系统行为或计划明确要求真机的项目，才保留真机验收缺口，并说明模拟器不能覆盖的具体风险；模拟器通过不得宣称这些真机项目通过。

```bash
# 检查格式（CI 必须通过）
./gradlew spotlessCheck

# 自动修复格式
./gradlew spotlessApply

# 环境与正式签名预检（不构建、不安装）
python scripts/build-android.py check --signing

# 开发 APK；与正式安装隔离
python scripts/build-android.py debug

# 正式候选：R8、外部签名、校验及产物清单；不自动跑全量或安装
python scripts/build-android.py candidate

# 无密钥 CI 只生成明确标注的未签名候选
python scripts/build-android.py candidate --unsigned

# 核对既有候选；有安装授权时才执行独立的 install 命令
python scripts/build-android.py verify --artifact "<APK绝对路径>"
python scripts/build-android.py install --artifact "<APK绝对路径>" --serial "<本次确认的设备>"

# 运行单元测试
./gradlew testReleaseUnitTest

# 运行单个测试类
./gradlew :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.SomeTest"
```

底层 `:app:assembleRelease` 仍可用于开发，但输出是未签名中间产物。重型 Gradle 验证继续经上文协调器串行执行；统一构建入口已调用协调器，不要再从外层嵌套同一协调器任务。

本 fork 禁止 `-Pinclude-telemetry`、`-Penable-updater`、`-Pdisable-code-shrink`；Gradle 配置会直接拒绝。专项 AEX/EIS/Sync init 脚本仅用于各自隔离验收，不能混用，也不能作为正式交付路径。

## 架构

Mihon 是由 Android 应用、Mihon Desktop 和共享 Kotlin 模块组成的多平台代码库，采用分层架构：

| 模块 | 职责 |
|---|---|
| `app/` | 表现层：Compose 页面、Activity、DI wiring |
| `app-desktop/` | Desktop 表现层、平台 adapter、运行时 wiring 与桌面端测试 |
| `test-desktop/` | Desktop E2E 测试客户端与 Robot API |
| `domain/` | 业务逻辑：用例、领域模型、仓库接口 |
| `data/` | 数据层：SQLDelight 数据库、仓库实现、映射 |
| `presentation-core/` | 跨页面复用的 Compose 组件 |
| `core/common/` | 公共工具与 Kotlin 扩展 |
| `source-api/` | KMP 漫画源抽象，供扩展复用 |
| `source-local/` | 本地文件源 |
| `i18n/` | Moko 字符串资源 |

### 包名

因 Tachiyomi → Mihon 迁移历史，仓库存在多个包名前缀：

- `eu.kanade.tachiyomi.*`：app 模块和多数旧代码
- `tachiyomi.domain.*` / `tachiyomi.data.*`：domain 与 data 模块
- `mihon.domain.*` / `mihon.feature.*`：较新的 Mihon 功能
- `mihon.desktop.*`：Mihon Desktop 的 UI、平台 adapter、运行时与独有能力

### 关键模式

- **依赖注入**：Android 与 Desktop 均使用 Injekt。Android 模块注册在 `app/src/main/java/eu/kanade/tachiyomi/di/`；Desktop wiring 位于 `app-desktop/src/main/kotlin/mihon/desktop/di/` 和 `DesktopUiDependencies.kt`。使用 `Injekt.get<T>()` 获取依赖，使用 `by injectLazy<T>()` 延迟注入。
- **导航**：Android 与 Desktop 均使用 Voyager（`cafe.adriel.voyager`）。Screen 实现 `cafe.adriel.voyager.core.screen.Screen`，导航通过 `Navigator` / `LocalNavigator`。
- **数据库**：SQLDelight + 协程。schema 与 migration 的唯一权威位于 `data/src/commonMain/sqldelight/`，生成查询在 `tachiyomi.data.*.db`；不得重建 `data/src/main/sqldelight/` 镜像。
- **图片加载**：Coil 3，自定义 fetcher / decoder 位于 `app/src/main/java/eu/kanade/tachiyomi/data/coil/`。
- **偏好设置**：`tachiyomi.core.common.preference` 封装 AndroidX DataStore / SharedPreferences。

### 构建逻辑

自定义 Gradle 插件位于 `buildSrc/src/main/kotlin/`：

- `mihon.android.application`：应用基础配置
- `mihon.library`：库模块配置
- `mihon.code.lint`：Spotless + ktlint

依赖版本由 `gradle/*.versions.toml` 管理。

## 测试政策：必须覆盖集成点

仅有 domain 单元测试不够。任何涉及导航、DI wiring、Screen / Tab、HTTP / API 的变更，必须加入对应集成级测试。影响集成点却只有 domain 测试的改动不得合并。

### 1. 导航类型安全测试

**适用场景**：新增或修改 Screen / Tab，或修改 `navigator.push()` / `navigator.replace()`。

**测试要求**：

- 验证传给 `navigator.push()` 的对象符合当前 Voyager 导航上下文：
  - `TabNavigator` 中只能设置 `Tab`：`tabNavigator.current = ...`。
  - 普通 `Screen` 必须进入嵌套 `Navigator`，不能直接作为 Tab。
  - 普通 `Navigator` 中的对象必须实现 `cafe.adriel.voyager.core.screen.Screen`。
- JVM 测试必须实例化每个 Screen / Tab，并断言接口正确：

```kotlin
@Test
fun `MangaDetailScreen 是 Screen 不是 Tab`() {
    val screen = MangaDetailScreen(mangaId = 1L)
    assertThat(screen).isInstanceOf(Screen::class.java)
    assertThat(screen).isNotInstanceOf(Tab::class.java)
}
```

- 每个 `navigator.push()` 调用点都要测试推入类型与导航上下文兼容。
- 若在 `TabNavigator` 内使用 `LocalNavigator`，必须测试确实使用了嵌套 `Navigator`，而不是直接使用 tab navigator。

**常见坑**：Tab 的 `Content()` 中，`LocalNavigator.currentOrThrow` 可能解析到包裹 `TabNavigator` 的父 `Navigator`；若没有父 `Navigator`，向 tab navigator 推入非 Tab 的 Screen 会在运行时 `ClassCastException`。必须用测试验证导航层级。

### 2. DI Wiring 测试

**适用场景**：新增 Injekt 绑定、新增 `Injekt.get<T>()` 调用、修改 DI 模块。

**测试要求**：

- 初始化全部或相关 DI 模块，并断言每个注册类型都能解析。

```kotlin
@Test
fun `所有 DI 绑定都能解析`() {
    AppModule.register()
    DomainModule.register()

    assertNotNull(Injekt.get<GetLibraryManga>())
    assertNotNull(Injekt.get<SourceManager>())
}
```

- 在 Composable 中新增 `Injekt.get<T>()` 时，必须把该类型加入 DI wiring 测试。

### 3. HTTP / API 集成测试（MockWebServer）

**适用场景**：修改 HTTP 客户端、源实现、API 解析、页面加载逻辑。

**测试要求**：

- 使用 `okhttp3.mockwebserver.MockWebServer` 注入真实形状响应，覆盖成功、空数据、错误、畸形 JSON。
- 测试从原始 HTTP 响应到领域对象的完整解析路径，不得 mock parser。

```kotlin
@Test
fun `MangaDex 源能解析真实章节页响应`() {
    server.enqueue(MockResponse().setBody(realPageListJson))
    val pages = source.getPageList(chapter)
    assertThat(pages).isNotEmpty()
    assertThat(pages.first().imageUrl).isNotBlank()
}

@Test
fun `源遇到空页列表不会崩溃`() {
    server.enqueue(MockResponse().setBody("""{"result":"ok","data":[]}"""))
    val pages = source.getPageList(chapter)
    // 应返回空列表或抛出明确异常；
    // 不得静默返回会破坏阅读器的无意义结果。
}
```

最低覆盖：成功响应、空/缺失数据、HTTP 403 / 429 / 500、畸形响应体。

### 4. Screen 实例化冒烟测试

**适用场景**：新增或修改 Screen / Tab。

**测试要求**：在 JVM 上用代表性参数实例化每个 Screen / Tab，捕获序列化问题、默认值缺失和构造器错误。

```kotlin
@Test
fun `所有页面都能实例化`() {
    MangaDetailScreen(mangaId = 1L)
    SourceBrowseScreen(sourceId = 1L)
    DesktopReaderScreen(
        chapterTitle = "Ch 1",
        pageUrls = listOf("https://example.com/1.jpg"),
        isWebtoon = false,
        sourceId = 1L,
        chapterUrl = "/chapter/1",
        chapterId = 1L,
        progressTracker = mockProgressTracker,
    )
}
```

### 5. UI Wiring 变更的红绿 TDD

涉及导航、DI、Screen wiring 时必须严格按以下顺序：

1. **红**：先写覆盖集成点的失败测试（导航 push、DI 解析、HTTP 解析等），并确认失败原因正确。
2. **绿**：写最小实现让测试通过。
3. **重构**：清理后重新运行测试。

以下都属于 UI wiring 变更：

- 新增 Screen / Tab
- 新增或修改 `navigator.push()` / `navigator.replace()`
- 在 Composable 中新增 `Injekt.get<>()` 或 `injectLazy<>()`
- 修改导航层级，例如在 `TabNavigator` 中嵌套 `Navigator`
- 修改源的 HTTP 获取或解析方式

**规则**：新增 `navigator.push()`、`Injekt.get()` 或 HTTP 端点时，必须有对应测试能在其损坏时失败，否则不可合并。

### 6. 合并前测试清单

| 变更类型 | 必须测试 |
|---|---|
| 新增/修改 Screen 或 Tab | Screen 实例化测试 + 导航类型测试 |
| 新增 `navigator.push(X)` | 测试 `X` 与当前导航上下文兼容 |
| Composable 新增 `Injekt.get<T>()` | `T` 纳入 DI wiring 测试 |
| 新增/修改 HTTP 解析 | MockWebServer 成功 + 失败用例 |
| 新增 domain use case | use case 单元测试 |

---

## HTML 双端交互 DEMO

### 位置与用途

- DEMO 位于 `docs/prototypes/multi-device-sync/`，并列审阅入口是 `index.html`；`device.html` 仅用于单端隔离调试。完整交互基线、演示顺序和已知边界以同目录 `README.md` 为准。
- 该目录是 Windows Desktop 与 Android 共用的交互原型资产，可在设计其他双端功能时复用 Mihon 外壳、导航、主题、图标、面板、列表和演示工具栏。新增独立原型时优先在 `docs/prototypes/` 下建立语义清晰的同级目录；只有与多设备同步直接相关的交互才继续写入 `multi-device-sync/`。
- DEMO 只用于确认信息架构、界面状态和操作路径。它不连接真实 GitHub/Git 服务，不代表生产同步、持久化、系统后台任务或跨设备通信已实现，也不能代替 Android/Desktop 构建与运行验收。

### 文件职责

| 文件 | 职责 |
|---|---|
| `index.html`、`preview.js` | Windows 与 Android 并列容器、共享场景和应用外演示工具栏 |
| `device.html`、`app.js` | 单端入口、Mihon 页面与交互 wiring |
| `styles.css`、`ui-view.js` | 双端外观、主题、图标及可复用渲染组件 |
| `sync-interactions.js` | 同步面板及仅用于交互审阅的场景状态 |
| `sync-model.js` | 本地内存操作日志与设备间演示模型 |
| `*.test.cjs` | 模型、双端契约、浏览器布局及交互验证 |

### 开发规范

- 修改前先阅读该目录 `README.md`，确认当前已审核的交互、演示边界和对应源码依据。涉及长期交互规则、入口、状态或已知限制的变化，须同步更新 README。
- 默认同时维护并列的 Windows 与 Android 预览。共享语义、数据和操作结果保持一致；导航、尺寸或平台原生控件确有差异时限制在视图层，并在 README 说明原因。不得为了演示方便改变生产需求语义。
- 优先复用现有外壳、组件、图标和 `data-testid`，避免另建视觉体系。界面应贴合仓库当前 Mihon 实现；需要还原现有界面时先查对应 Compose 源码，不凭印象重画。
- 交互原型可使用满足审阅所需的最小模拟逻辑，但必须清楚区分 UI 场景与同步模型。纯展示场景放在 `sync-interactions.js`；操作因果、设备隔离或队列语义才进入 `sync-model.js`。不要为修饰界面顺带修复或扩张未获要求的模型能力。
- `index.html` 的两个 iframe 共享演示数据，但导航、焦点、滚动、选择、临时通知和计时器保持设备隔离。修改一端或重绘另一端时，不得抢焦点、泄漏未同步操作或重置对方的局部 UI 状态。
- 保持原型完全本地可运行：不引入 CDN、真实账号、真实令牌、远端请求或生产密钥。GitHub 授权只能使用明确标注的本地模拟页和演示数据。
- 临时反馈只在当前面板会话内显示，收起后不回放；危险或批量操作须提供与 Mihon 现有交互一致的确认和结果反馈。无编程背景用户能看到的文案应使用产品语言，不暴露 Git、提交、分支或队列实现细节。
- 与其他会话并行开发时，只编辑和提交本任务涉及的原型文件；已有未提交改动视为其他工作，禁止清理、覆盖或混入提交。

### 打开与验证

可直接打开 `docs/prototypes/multi-device-sync/index.html`。需要 HTTP 预览时，从仓库根目录运行：

```powershell
python -m http.server 50943 --directory docs/prototypes/multi-device-sync
```

验证按改动范围选择，不将 DEMO 变更升级为全量产品构建：

- 纯文案或说明：检查目标页面和 `git diff --check`。
- JavaScript 交互：运行受影响的 `*.test.cjs`，并对修改的脚本执行 `node --check`。
- 双端布局、导航或状态隔离：至少运行对应浏览器测试；共享预览改动包含 `parallel-preview.test.cjs`。
- `sync-model.js` 行为：先按红绿重构更新 `sync-model.test.cjs`，再运行受影响的浏览器交互测试。

浏览器测试使用本机 Chrome 与 `playwright-core`；需要时在当前会话设置 `PLAYWRIGHT_CORE_PATH`。常用完整 DEMO 验证为：

```powershell
node --test docs/prototypes/multi-device-sync/sync-model.test.cjs docs/prototypes/multi-device-sync/ui-view.test.cjs docs/prototypes/multi-device-sync/ui-browser.test.cjs docs/prototypes/multi-device-sync/library-sync.test.cjs docs/prototypes/multi-device-sync/batch-sync.test.cjs docs/prototypes/multi-device-sync/parallel-preview.test.cjs docs/prototypes/multi-device-sync/sync-interactions.test.cjs
```

## 桌面端自动化测试

Mihon Desktop 包含完整 E2E 自动化测试系统。

### 快速命令

跨会话的流程迁移只接入规则、工具和必要验证，不自动启动目标会话、产品开发或原生验收。目标会话的继续开发由用户在原应用会话中启动；不得通过 `codex exec resume` 代启动。

```bash
# 当前变更的定向测试；替换为实际受影响的测试类
python scripts/gradle-coordinator.py run --key desktop-focused -- ./gradlew :app-desktop:jvmTest --tests "<测试类>"

# 日常体验构建；不隐含完整测试或正式版本分配
python scripts/gradle-coordinator.py run --key desktop-preview -- bash scripts/build-desktop.sh preview
```

完整模块命令仅在显式里程碑或已批准的具体扩大验证中使用。Windows 的 Bash 应取本机 Git Bash 实际路径，不能把 WindowsApps/WSL 启动器当成本机 Bash。

### macOS 验收经验入口

执行 macOS 正式应用、安全存储、窗口或原生交互验收前，必须阅读
[macOS 验收经验与维护约束](docs/automation/MACOS_ACCEPTANCE.md)，并核对本次环境与产物。
Mac 原生验收统一从 `python3 scripts/mac-acceptance.py` 进入；它在 Mac 上运行，可通过已有 SSH 入口调用，负责有界亮屏复核、LaunchServices 启动、精确实例检查、原生场景和隔离关停。新场景复用 `mac_acceptance_session.py` 的会话保护，不另写锁屏推断或绕过它。只读 `acceptance-preflight.py` 的 `NOT_RUN` 不是最终原生验收结论，应继续执行已授权的统一入口；不得因 SSH、息屏或锁定字段缺失直接要求用户解锁。只有恢复后的具体失败证据才能报告对应阻塞。
统一入口或新规则在当前分支缺失时，核对这两个文件及经验文档的 Git 历史并定向整合已验证版本；不得把旧文档中的历史路径/PID当成本次默认值，也不得在没有验证的情况下重建一套临时验收脚本。此次修正规则、共享保护、执行入口和回归须随同一提交传播。
统一入口已在 macOS 14.8.4 经 SSH 使用两个全新 profile 完成真实原生场景及精确关停，其中一次从自然息屏恢复，无需人工解锁。适用范围与证据见 Mac 指南的“当前状态”；后续任务仍须核对本轮候选能力，复用该入口及共享保护，不能把此试点当成其他功能或平台已验收。场景接口缺失、未使用的 JMX 监听缺失，不得解释为锁屏或 SSH 无法操作 GUI。
采用该文档经验的 Agent 若发现新经验、适用边界变化或旧结论失效，
必须在本次任务收口或交接前按文档约束更新，不得只留在聊天或临时日志中。

### 测试文档

- 用户指南：`docs/automation/TEST_GUIDE.md`
- API 参考：`docs/automation/API_REFERENCE.md`
- 进度追踪：`docs/automation/TASK_TRACKER.md`

### 关键文件

| 文件 | 说明 |
|---|---|
| `app-desktop/src/main/kotlin/mihon/desktop/test/` | 测试基础设施：TestMode、TestState、HTTP Server |
| `test-desktop/src/main/kotlin/mihon/test/desktop/` | 测试客户端库：Robot 模式、HTTP 客户端 |
| `app-desktop/src/test/kotlin/mihon/desktop/smoke/` | 冒烟测试套件 |

### 测试模式启动

Windows 使用本轮日志 `Final unpacked EXE:` 的实际路径，先核对空闲端口和独立 profile：

```powershell
& "<本轮 Final unpacked EXE 的绝对路径>" --test-mode --test-profile="<本轮独立目录>" --test-http-port=49163 --test-jmx-port=49164
```

macOS 本机或通过 `ssh mbp` / `ssh mbp-lan`，按下列经验入口通过 LaunchServices 启动本轮精确应用包：

```bash
open -n -W -a "<本轮 Final macOS app 的绝对路径>" --args --test-mode --test-profile="<本轮独立目录>" --test-http-port=49163 --test-jmx-port=49164
```

纯 HTTP 验证可加 `--headless`；原生输入和窗口验收必须保留图形界面。占位符须替换为本次已核对的路径和端口，不能照搬历史安装目录或连接旧实例。

测试模式提供 HTTP API：

- `GET /test/state`：获取应用状态
- `POST /test/navigate/{screen}`：导航
- `POST /test/action/{action}`：执行动作

Test Mode 自身不提供桌面截图 API，也不得通过 Test Mode 接口读取桌面屏幕像素；视觉验证可使用不需要系统录屏权限的离屏测试。经授权的外部 Computer Use 属于独立工具链，可按下述工具规则观察精确隔离候选窗口。

外部 Computer Use 与 Test Mode 是不同的验收能力。只有任务已授权且当前工具规则允许时，才可用外部工具观察本次精确隔离的候选窗口。先查看当前会话的技能/插件说明，并在实际 Computer Use Node REPL 中对 `@oai/sky` 做只读健康探测；不能仅凭当前 shell 无法导入、没有独立 `computer` 工具名或旧 `cua_repl` 不可用就认定该工具不可用。若导入和只读窗口枚举健康，使用同一已加载插件的文档：提供 `sky.documentation` 时可调用；该方法缺失时直接读取该插件目录下 `docs/guidance.md` 与小写的 `docs/api.md`。缺少文档方法表示接口差异，不表示 Computer Use 不可用。记录实际探测证据和影响；某个平台受阻时保留其缺口并继续独立可完成项。

**Computer Use 集中执行与及时释放**：按 [集中操作与归还桌面](docs/automation/TEST_GUIDE.md#computer-use-集中操作与归还桌面) 执行，适用于 Windows、本机操作远程 Mac 窗口及模拟器等会占用用户桌面的原生操作。

- 先完成代码、构建、测试数据、可离线完成的预检与审查，并准备报告和提交内容，将当前授权范围内已就绪、可连续执行的原生验收项集中安排在本轮末尾；不为凑批次增加测试、等待整个 roadmap 或破坏场景隔离。
- 开始前简短告知操作范围和预计占用时间；同一桌面只允许一个执行者派发输入。集中执行仍须逐动作观察，沿用最新目标、截图和焦点保护，不批量盲发输入。
- 前台操作期间只做必要交互及即时结果核对。需要修改代码、构建、长等待、请求用户输入或深入分析时，先按指南收尾并归还桌面；当前 Windows 版本须随即用 `final` 结束本轮，后续工作留到下一轮。用户接管或停止 Computer Use 时立即停止输入，不抢回焦点。
- 成功、失败或中断均须收尾：停止本批后续输入、截图及自动激活，精确关停本次测试实例及自建辅助进程；用户要求留下的体验应用可保持打开，但不得继续控制。**本机对照已确认 `node_repl.js_reset` 后控制提示仍保留，直到根会话最终回复出现才消失；reset 只能作为可选的执行端清理，不能作为主动释放桌面的办法。** 不调用私有协议，不全局杀进程，也不假定子代理结束能释放根会话控制。
- 最后一次原生观察后只做必要关停、简短证据落盘与已准备好的本批提交，立即输出 `final`；不要在这一阶段继续编译、查资料、写长报告或等待代理。报告说明实际完成项及未完成项，不把“停止输入”宣称为“桌面已释放”。再次进入 Computer Use 时重新告知并核对目标。

Windows 输入桌面、活动显示拓扑与实际捕获/输入须分层核对。`display-topology` 的 `NO_ACTIVE_DISPLAY_PATH` 不代表锁屏或未安装 Computer Use；正常输入桌面及正的路径容量也不证明可捕获。实际捕获失败时按 [Test Guide](docs/automation/TEST_GUIDE.md) 做一次有界恢复和具体诊断，保留错误码；显示环境没有改变时，不重复捕获、重建产品或扩大测试来替代缺失的显示输出。不得仅凭 `WinDisc` 名称或历史渲染错误判定原因。

Windows 条件恢复后按 [原生小场景操作顺序](docs/automation/TEST_GUIDE.md#windows-原生小场景与正常关停) 使用全新 profile 续验。一次输入后若截图仍为动画过渡帧，按指南最多补两次观察，仍不稳定则记未确认，不重复派发输入；原生结果单独记录，不回改 preview manifest 的 `nativeInteraction=NOT_RUN`。

macOS 验收中，SSH 连通性、显示器息屏、图形会话锁屏和 Keychain 可用性是独立观察项，不以其中一项推断其他项。锁屏时停止原生输入，等待用户解锁后重新核对；不代改系统权限或锁屏设置。Keychain 以本次正式应用实际读写为准。

`scripts/acceptance-preflight.py` 只读检查精确候选、设备或会话，不启动应用、不安装、不发送输入。状态 `PASS`、`ENV_BLOCKED`、`TOOL_FAIL`、`NOT_RUN` 表示预检本身的结果；`PRODUCT_FAIL` 只用于真实产品行为断言失败，不能由环境/工具前置检查代报。每个平台、原生输入和视觉层分别记录；静态候选检查不证明签名、完整 provenance 或产品行为，未实际执行的层级保持 `NOT_RUN`。

详见 `docs/automation/TEST_GUIDE.md`。
