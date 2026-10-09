# 测试流程落地与验收证据

迁入说明（2026-10-09）：本文保留源 `609f` 工作树的流程与历史验收记录，文中的过程目录相对于 `D:/Codex/worktrees/609f/mihon/`。迁入 `f235` 不代表目标历史阅读器已重新验收，不改变目标产品计划的完成状态；开发由用户在原会话启动。

日期：2026-10-06。实施基线 `92693c1a44`；[专项计划](../roadmap/2026-10-06-testing-workflow-rollout.md)，[问题审计及旧流程图](../automation/TEST_WORKFLOW_REVIEW_2026-10-06.md)，[当前流程图与操作指南](../automation/TEST_GUIDE.md)。本轮实施流程、构建入口、只读预检及 Mac 隔离原生执行入口，不修改产品功能，不追认历史会话未完成的验收。

当前结论：Windows/Mac 体验构建及 Android/Mac 原生试点已通过；Android 已在新的独立 AVD 再次通过。Windows 原生激活/捕获仍有环境缺口：当前系统报告活动显示路径容量为 0，实际捕获失败，临时亮屏没有恢复。下文保留历次失败；Mac 两次完整实测及 Windows/Android 续验分别见后文。

## 规则与实现

| 场景 | 本轮落地的要求与停止条件 |
|---|---|
| 文案或纯文档 | 目标页面/文案、链接和 diff 检查；不触发产品测试、完整构建或独立审查。共享布局变化按实际传播范围验证。 |
| 已有动作增加按钮 | focused 行为红绿、真实点击及必要导航/DI 接线、受影响平台用户路径；不要求完整模块。 |
| 数据迁移、安全、共享协议 | 当次覆盖真实存储、失败恢复与边界，由未实施者审查；不能推迟关键正确性到功能定型。 |
| 稳定版首次完整测试失败 | 保留基线，关闭相关失败并定向补验；有效未受影响结果复用。扩大必须指出具体缺口，不因提交、修复或会话结束重跑全量。 |

- 日常默认体验模式；完整发布矩阵由用户明确指定里程碑。日常有具体证据时可申请最小模块验证，不能把模块验证和整个发布矩阵混为一谈。
- Desktop `preview` 不跑产品测试、不分配正式版本、不覆盖日常安装/正式产物；每次产物独立，清单记录源码、差异指纹及分层状态。Windows 保留现有 production 扩展运行时检查，macOS 应用包构建与运行验收分开。
- Android 继续复用官方 `debug`、`verify`、`install` 入口和独立 `.dev` 身份。本轮没有创建新的 Android 构建体系。
- 预检只读。候选形状/设备/会话通过不代表产品行为通过；Windows Computer Use 还须在实际 Node 会话中探测，不能以 shell 导入失败、缺少 `computer` 工具名或 `sky.documentation` 方法认定能力不存在。
- 外部原生工具与 Test Mode 分开；`ENV_BLOCKED`、`TOOL_FAIL`、`PRODUCT_FAIL`、`NOT_RUN` 分别报告。预检本身不执行产品动作，因此不会产生 `PRODUCT_FAIL`。

## 定向验证与独立审查

两名实施代理分别负责脚本和文档，主代理审查隔离、风险门槛和真实平台路径。没有运行产品完整模块或全量矩阵。

本次覆盖 14 个文件，超过文件/行数审查提示，主要来自两个入口、契约测试及原有规则入口的同步说明。它们需要同时交付以避免“规则要求体验构建但入口仍隐含全量”；没有拆分不可独立使用的上下文，也没有新增状态服务或改动业务实现。发布目录隔离、环境变量恢复及失败状态由主代理独立核对；环境缺口保留为未完成验收。

| 验证 | 结果与覆盖 |
|---|---|
| `python scripts/tests/desktop-preview-test.py` | Windows 8 项通过；Mac 专属 4 项在 Windows 跳过后，另在真实 Mac 上全部通过。执行真实构建脚本，以假 Gradle/外部 validator 限定测试边界；这不算真实产品构建。 |
| `python scripts/tests/acceptance-preflight-test.py` | 14 项通过；覆盖错误候选、失败 manifest、明确设备选择、离线/锁屏、工具错误、未知及 API 36 电源字段。假 adb 拒绝所有不在只读白名单内的命令。 |
| Bash/Python/文档检查 | Bash 语法、Python 3.9 语法兼容及 `git diff --check` 通过；最终提交前核对新增链接和实际产物。 |

首次红测与修复事实：

1. 新 `preview` 和预检入口在旧实现上失败；实现后针对相同调用范围转绿。
2. 主审发现 Windows 同进程环境变量泄漏。成功/失败 × 原值有/无的 4 个分支先复现，再验证 `finally` 恢复。
3. Mac 实跑发现普通目录不能直接传给 `diskutil info`；先定位卷设备。随后正确复现临时目录未清理/失败目录未报告，修复生命周期并复验同一范围。
4. API 36 真实电源输出使用 `mWakefulness=Awake`，没有 `mInteractive`。补充实际形状的失败测试；显式休眠、矛盾、未知或转换中状态都不会误报通过。

原始日志和本轮截图存放在本工作树忽略目录 `app-desktop/tmp/workflow-tf03/`；重型构建日志在 `.gradle-coordinator/`。它们是本轮运行材料，不是另一个长期状态权威，也不能替代下面明确的验收结论。

## 平台实际结果

### Windows

- `preview` 真实构建通过，Gradle 用时 1 分 9 秒，54 个任务中 10 个来自缓存、13 个 up-to-date。日志确认未运行 JVM 完整测试，AppVersion 与正式 Android 版本文件未改变。
- 本轮日志 `Final unpacked EXE:`：`app-desktop/artifacts/preview/windows/20261005T201901Z-d5bd0008/Mihon-Desktop-0.11.19.73.92693c1-unpacked/Mihon Desktop.exe`。同次 `preview-manifest.json` 记录 `build=PASS`、`sourceIntegrity=PASS`、`productionRuntime=PASS`、`nativeInteraction=NOT_RUN`。
- 既有 production 运行检查实际安装打包内扩展 fixture，返回源结果并核对版本；这是运行证据，不能代替本轮 GUI 原生输入。
- CLI 候选和正常输入桌面预检 `PASS`；当前会话 Computer Use 层为 `NOT_RUN`，所以 CLI 总结果为 `NOT_RUN`、退出 2，符合它不代探会话工具的边界。
- 实际 Node `@oai/sky` 导入及窗口枚举成功，返回精确产物路径对应的窗口和版本标题；本轮接口没有 `sky.documentation`，读取同插件 `docs/guidance.md`、`docs/api.md` 与 `docs/confirmations.md` 后继续。
- **原生/视觉未通过**：隐藏启动与正常图形启动分别观察到窗口枚举成功，但截图只显示桌面背景，激活返回 `failed to activate captured window`；每个实例只按工具规则重新选择后恢复一次，仍失败即停止输入。不能由此断言没有 Computer Use 能力，也没有证实锁屏、启动方式或产品隐私机制是原因。
- 正常实例的生产 HTTP health、HomeScreen、已挂载同步入口及其 AWT 活动窗口元数据可读；这些不代替原生点击或可见性。没有通过业务 HTTP 打开面板来冒充原生验收，也没有更改安全/隐私设置。
- 第一实例已通过 `/test/shutdown` 返回 202，并确认 launcher 和 runtime 都退出；第二实例暂留待用户确认实际窗口是否可见。本轮平台缺口是外部 Computer Use 对此候选的激活/捕获，以及后续原生入口/返回，不是再跑一次完整产品测试。

### Android

- 官方 Debug 构建及独立安装通过。第一次编译成功（3 分 41 秒），但脚本审查修复期间来源指纹改变，候选被正确拒绝；源码冻结后第二次候选成功（27 秒，333 个任务中 316 个 up-to-date）。没有使用被拒绝的 APK。
- 产物：`app/artifacts/android/0.19.4-aex.23-vc41-92693c1a44-debug-4cfbbdca7aac/Mihon-Fork-0.19.4-aex.23-vc41-debug-universal.apk`。SHA-256：`644aac2d91c86e27a9aca63f93da5c259f2f6f5d7c95e5e25446ddcb6937063a`。
- 专用新 AVD，Android API 36、x86_64；通过实际 AVD 名称核对身份。已有两个模拟器未操作；没有操作实体设备。
- 从 APK 的真实 launchable activity 核对主入口。Debug 同时包含 UI 审阅入口，通用 launcher resolve 会返回系统选择器，不能据此断言安装失败；本轮明确启动 `eu.kanade.tachiyomi.ui.main.MainActivity`。
- 实际原生路径：主题欢迎页 → 点击 Next → 存储目录页 → 系统 Back → 主题欢迎页。每次输入前核对前台包、未锁屏和当前层级；点击坐标来自当次控件 bounds。前后 XML 断言及截图通过，返回行为与 production `OnboardingScreen` 的 BackHandler 一致。
- 新预检在真实设备/前台上返回 `PASS`；其 `nativeInteraction` 仍为 `NOT_RUN`，原生路径通过依据是独立的 `android-native-result.json`，没有修改预检声明来冒充已执行。
- 完成后按本轮 AVD 身份精确关停模拟器。没有授予存储/账户权限、写入凭据、执行同步或验收 Release/R8。此结果只证明新装 Debug 的小范围原生验收链，不能外推为全应用通过。

### macOS

- 通过现有 SSH 入口在真实 macOS 14.8.4、Python 3.9.6 上运行 4 项脚本回归，全部通过。
- 首次预检报告 `ENV_BLOCKED`，原始观察为 `CGSSessionScreenIsLocked=true` 和 APFS 可用空间约 2.88 GiB。其中“需要用户解锁”的解释不成立，已按下文复核更正。空间仍低于本轮构建/隔离工作区预留的 10 GiB；该阈值是本轮成本判断，不是项目固定门槛。
- 环境探测借用既有 `/Applications/Mihon Desktop.app` 的文件形状作定位，没有启动或操作它；它不是本轮候选，不能作为本轮构建/运行通过证据。
- 当前 checkout 与远端现有 checkout/进程不同，远端还有其他任务的未提交改动和应用实例；均未修改、终止或复用。
- 本轮真实 Mac 体验包构建、LaunchServices 启动、原生入口/返回及视觉保持 `NOT_RUN`。先前请求用户解锁的要求撤回；当前独立缺口是空间前置及本轮候选的实际验收，不清理用户文件、不扩大测试。恢复后只补 Mac 缺口，无须重跑 Windows/Android 的有效证据。

### 息屏复核与更正（2026-10-06 16:45–16:55）

用户指出 Mac 只是息屏。对同一会话读取显示器、CoreGraphics 会话、IOConsoleUsers 和 AppKit 前台，再进行一次有界亮屏对照：

| 观察项 | 亮屏前 | `caffeinate -u -t 8` 后约两秒 |
|---|---|---|
| 主显示器 | asleep=true、active=false、online=true | asleep=false、active=true、online=true |
| 会话锁定标志 | true | 字段缺失 |
| 前台应用 | `com.apple.loginwindow` | `mihon.desktop` |

没有输入密码或执行解锁，没有改变系统安全/锁屏设置，也没有向已有 Mihon 实例发送输入；8 秒的临时断言自行退出。这证明本次“必须由用户解锁”的判断错误，不证明其他真正锁屏场景可以绕过。原始对照保存在 `app-desktop/tmp/workflow-tf03/mac-display-wake-comparison.json`。

修正 `acceptance-preflight.py`：单独读取主显示器状态；息屏/显示器不可用时，锁定判断保持 `NOT_RUN` 并保留原始字段，提示先亮屏后复核。亮屏后仍报告锁定则继续阻塞；锁定字段缺失不自动变成可操作。新增 4 项测试（含多个状态组合），先复现旧判断失败，再与原 14 项一起通过。真实 Mac 复验显示 `main-display=PASS`、原生事件权限 `PASS`、锁定字段缺失待目标窗口核对；磁盘约 2.86 GiB 仍独立报告 `ENV_BLOCKED`。本轮未重新构建或运行产品测试。

### Mac 执行链补齐：已有经验必须落实到实际输入路径

上一轮只修正只读预检，`mac-sync-native-acceptance.py` 的实际输入路径仍复制单字段锁屏判断，且通用 Test Guide 仍示例 SSH 直接执行应用包 launcher。这是本轮确认的流程缺口；文档可被读到不等于执行路径已遵守经验。另查明 `92f1617fd3`、`2eba179e1c` 已记录相同息屏冲突与临时亮屏恢复，但未在本工作树祖先历史中，本轮将来源保留在 Mac 指南。

本批将共享会话判断、一次有界恢复、LaunchServices 启动、精确 PID/profile/端口归属、前台/窗口/AX 命中、原生场景和自有实例关停纳入同一执行入口。复用现有 native 场景，不新增产品 UI 或通用控件树；涉及多个脚本、focused 测试和规则文档是同一验收链的内聚修改。主代理独立审查目标隔离、输入保护和清理路径，实施代理负责红绿与修复；产品测试/正式构建为零。

实机使用既有 `11.19.75` 正式包的 APFS 独立副本及全新 profile，源包和另外三个既有实例未修改。该候选仅用于验证验收工具，不能冒充本轮源码重构建。运行前真实观察为 `asleep=true`、`reportedLockFlag=true`、原生事件权限可用，保存在 `app-desktop/tmp/workflow-tf03/mac-session-before.json`；副本来源与路径保存在同目录 `mac-session-context.json`。实际运行结果在验证结束后写入本节。

补充实际结果：Mac 脚本 focused 最终 17 项通过，相关只读预检 19 项通过，Python 3.9 语法及 diff 检查通过。第一次实跑 `mac-session-run-1.json` 自动亮屏成功、锁定字段消失，但同进程仍报告 loginwindow；独立新进程报告 Mihon 前台。按此差异增加每次前台观察前的有界 CFRunLoop 刷新，并去掉无输入启动阶段仅凭 loginwindow 阻塞的条件；实际输入保护不放宽。该差异支持刷新需求，不单独证明所有缓存机制。

第二次实跑 `mac-session-run-2.json`：LaunchServices 实际启动独立包，PID 69204、独立 profile 与 HTTP 59463 已核对；`/test/health` 和 `/test/sync` 可读，`/test/sync/ui` 返回 404。JMX 59464 未监听，另有 JVM 随机监听端口。不能继续该原生场景，也不能判为 SSH 无法 GUI。runner 将 HTTP 404 按 URLError 等待至 30 秒，仍需改为明确的候选能力缺失；监听端口门槛须核对产品契约，不盲目要求未启动的 JMX 服务。

暂停前，主代理按精确命令行、app/profile/HTTP 端口归属确认自有实例，并读取到其真实前台 PID 为 69204；仅请求该实例正常 shutdown，返回 202，应用及 open 包装器均已退出。结果在 `mac-session-cleanup.json`，全部 JSON 位于 `app-desktop/tmp/workflow-tf03/`，源包和其他实例未操作。未完成：候选能力与端口诊断收口、选择适用候选、两次独立 profile 的完整原生验收。累计本地周额度估算约 4.91% 时停止扩展，按用户 5% 上限暂停；此为成本估算，非账户实时扣减。

### Mac 追加授权后的实机收口

用户追加 3% 周额度后复用原 Sol-high 实施代理，主代理独立审查实例身份、输入保护及关停路径，并独立执行第二次原生验收。只补本平台已知缺口，没有运行产品完整模块或全量测试。

先核对候选与实际运行契约：已知的 70、72、75 既有实例均为 health 200、场景 `/test/sync/ui` 404，另一个历史 73 包已不完整；本轮不操作或关停这些已有实例。当前 `TestMode.kt` 只启动 HTTP 服务，没有启动声明参数对应的 JMX 服务。由此修正两个确定的工具问题：

- 身份从实际 HTTP 监听者和精确 app/profile/端口参数确定，不依赖可能缺失的场景接口；场景接口 404 立即归为 `CANDIDATE_CAPABILITY_MISSING`，不当网络失败重试，不归因于锁屏，仍可安全关停本次实例。
- `sync-main` 不要求未使用的 JMX 服务监听，保留兼容参数和启动前端口冲突检查。health、场景能力和原生结果独立记录。

新增两项回归先红后绿；原生入口合计 19 项在 Windows 通过，Mac Python 3.9.6 最终同样 19 项通过（0.086 秒）。Mac 首次出现的 7 项 fixture 路径失败来自 `/var` 与 `/private/var` 的同路径别名，测试夹具改为规范路径后消除；随后一项 HTTPError 夹具缺响应流的问题也已修正，不改变 production 身份约束。前轮共享会话保护、单次恢复及只读预检的 19 项证据仍适用，本轮没有改动共享保护或重复产品测试。

旧候选无法覆盖真实场景，因此按原授权补一次官方 `preview`：从本机精确提交建立浅仓库后传至新的 APFS 目录，保留原 Git SHA，不修改远端已有脏工作区。独立工作区通过 Gradle coordinator 串行执行 `bash scripts/build-desktop.sh preview`；3 分 21 秒，53 tasks（41 executed、12 from cache），退出 0。主代理核对真实日志、产物存在及构建后 tracked diff 为空；未分配正式版本。磁盘从约 2.41 GiB 到 1.85 GiB，没有清理用户文件。

本轮候选：

- 源码 `1f5a0b85335d1bdb23144da1ae095c364ec2a18e`，版本 `0.11.19.73.1f5a0b8`，源码 clean。production 输入 SHA-256：`048e44f1663490f81dc22ffcc880422ab0186ee74d297c2b4c6d41d107e85fc0`。
- Mac 本机实际产物：[Mihon Desktop.app](</private/tmp/mihon-mac-preview-8wu0p5p8/repo/app-desktop/artifacts/preview/macos/20261006T131045Z-69737/Mihon Desktop.app>)。这是 Mac 上的绝对路径，不是 Windows 安装路径。
- 本机保存的[构建清单](../../app-desktop/tmp/workflow-tf03/mac-preview-20261006T210858/preview-manifest.json)和[构建日志](../../app-desktop/tmp/workflow-tf03/mac-preview-20261006T210858/mac-preview.log)。构建清单的 runtime/native 保持 `NOT_RUN`；实际原生结果单独留证，不追改构建层结论。

两次均由 Windows 经 SSH 调用 Mac 的同一 `mac-acceptance.py`，使用官方 preview 应用和不同的全新 profile。主代理校验远端执行的 runner、共享保护、原生场景三文件 SHA-256 均与本地待交付版本一致；第二次不是复用第一轮进程或 HTTP 业务动作。

| 验证 | 首轮（实施代理） | 第二轮（主代理独立执行） |
|---|---|---|
| 初始会话 | 自然息屏、lock flag=true、loginwindow PID 176；入口一次 `caffeinate -u -t 8` 后亮屏、标志缺失 | 已亮屏，标志缺失；未重复恢复 |
| 隔离身份 | `profile-native-1`，app PID 70019 / open PID 70016，HTTP 59563 | `profile-native-2`，app PID 70143 / open PID 70140，HTTP 59573 |
| 输入前目标保护 | 精确新 PID 前台、focused、onScreen、AX hit 全部通过 | 同样通过；没有因已有其他 Mihon 实例而误定位 |
| 真实鼠标与键盘 | 鼠标打开生产同步面板；五控件 Tab / Shift+Tab 完整回环；Escape 还焦；Enter / Space 重开；最后关闭 | 全部同范围通过 |
| 退出 | 一次正常 shutdown；应用、包装器和辅助进程退出；端口无监听 | 同样通过；主代理额外只读核对 runner、app、open 均退出，59573/59574 无监听 |
| 原始结果 | [首轮报告](../../app-desktop/tmp/workflow-tf03/mac-preview-20261006T210858/native-run1.json)、[退出核对](../../app-desktop/tmp/workflow-tf03/mac-preview-20261006T210858/native-run1-exit.json) | [独立复验报告](../../app-desktop/tmp/workflow-tf03/mac-preview-20261006T210858/native-run2.json)、[退出核对](../../app-desktop/tmp/workflow-tf03/mac-preview-20261006T210858/native-run2-exit.json) |

两轮未输入密码、未要求人工解锁/置前台、未改系统权限或锁屏设置。首轮亮屏时系统曾自然回到旧实例 PID 55981，入口随后只激活核对过的新实例 70019；原生事件只发送给已确认目标。旧实例均未操作或终止。

结论：此次“息屏即必须人工解锁”和“SSH 无法 GUI”的流程误判已修复，并由实际重复执行证明这条原生路径可用。后续需要 Mac 原生验收的任务必须使用统一入口；预检 `NOT_RUN` 进入已授权的有界准备，不能作为最终阻塞直接收口。新增场景复用同一生命周期和保护，只补受影响用户路径；不自动增加所有任务的 Mac 测试。确实仍有会话冲突、权限不足或候选缺失时，报告具体层级及证据，不自动归因为用户锁屏，不无限重试。

### Windows/Android 追加授权续验（2026-10-06 至 2026-10-07）

用户另授权 3% 周额度完成两平台缺口。复用一名 GPT-6.1 Sol 子代理负责 Windows 诊断、预检修正及首验，主代理独立处理 Android 复跑、审查代码并核对 Windows 实际环境。先核对既有产物，未重新构建应用或运行产品全量测试；因当时内存余量约 3.3 GiB，新的 Android 与 Windows 实例串行运行，已有两个模拟器未操作。

**Android 独立复跑通过。** 统一 `build-android.py verify` 再次核验前轮 APK 的哈希、签名、manifest 和构建身份，继续使用 SHA-256 `644aac2d91c86e27a9aca63f93da5c259f2f6f5d7c95e5e25446ddcb6937063a`。新建专用 AVD `mihon-workflow-tf03-repeat-20261006`，API 36 / x86_64 / 1536 MiB / 2 cores，serial `emulator-5592`，独立 userdata，进程 PID 24596。通过 AVD 名称确认设备，统一安装入口验证实际安装身份后，按 APK 内实际声明的 `MainActivity` 冷启动，不进入另一个 UI 审阅 Activity。

主代理复用现有 `Device.guard()`，每次输入前核对未锁屏、目标包前台，每次 hierarchy 前后核对相同窗口；从当次唯一启用的 Next 的 clickable 父节点 bounds 取得点击位置。真实 Next → 存储步骤 → 系统 Back → 主题步骤均通过，存储和返回页面的截图也已独立观察。初次观察断言错误地期待标题改为 `Storage`；实际各步骤共用 `Welcome!`。该失败已保留，未重复派发 Next，也未改产品；重新核对真实控件后，以存储选择器/指南与主题选项的出现和消失完成断言。此规则同步写入 Test Guide，避免以后沿用错误标题判断。

运行后只读预检 `PASS`，原生通过依据仍为独立原生报告。再次核对 AVD 名称后只向本次 serial 发出 `emu kill`，确认 PID 24596 退出、该 serial 从 adb 消失；原先的 `emulator-5560`、`emulator-5586` 仍在。没有账户、存储授权、真实同步、实体设备或 Release/R8 操作。结果与图像在 [Android 独立复跑报告](../../app-desktop/tmp/workflow-tf03/android-repeat-20261006/native-result.json)、[预检](../../app-desktop/tmp/workflow-tf03/android-repeat-20261006/preflight.json)、[存储页](../../app-desktop/tmp/workflow-tf03/android-repeat-20261006/storage.png)、[返回页](../../app-desktop/tmp/workflow-tf03/android-repeat-20261006/back.png)。

**历史状态（显示条件恢复前）：Windows 尚未完成原生验收。** 复用已核对的前轮 preview `0.11.19.73.92693c1`，launcher SHA-256 为 `32df64c457c21885048d37977e7136da3565cf399da9e531bc46940cbb569d37`，清单保留其原有 dirty 源码指纹，不冒充本轮新构建。启动新的独立 `profile-native-1`，HTTP 59653 / JMX 参数 59654，launcher PID 1480 / runtime PID 48848。主代理核对实际命令行、父子关系与只读 UI 中的 PID；health 和已挂载 UI 正常。Node 中 `@oai/sky` 导入、窗口枚举、精确候选选择均成功；按当前插件完整阅读操作规范与 API，所有 Windows UI 操作限定使用该插件。

子代理首次捕获返回 `IGraphicsCaptureItemInterop.CreateForMonitor ... 0x80070057`，重新选择窗口后进行一次允许的激活恢复，仍返回 `failed to activate captured window`；主代理从自己的工具上下文对同一精确目标独立捕获，复现相同错误。没有发送任何点击、按键或业务 HTTP 动作。随后只读诊断取得以下事实：

| 检查 | 本次观察 | 不能外推的结论 |
|---|---|---|
| 会话与桌面 | app、Node 和 CUA helper 同为 session 1；WTS Active、active console 1；WinSta0 / Default 输入桌面 | 这些条件不等于截图可用 |
| 目标窗口 | visible、未最小化、矩形在逻辑屏幕内；display affinity 为 0 | 不是因该候选窗口隐藏、移到屏外或启用防捕获而拒绝，但仍不证明捕获成功 |
| 显示元数据 | `EnumDisplayMonitors` 仅返回 WinDisc；四个 GPU display device 均非 attachedToDesktop、没有 monitor | 不能只凭名称判断锁屏、物理设备状态或 CUA 不存在 |
| 活动路径 | `GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS)` 成功返回 0，active/mode capacity 均为 0 | 这是本次拓扑观察；未知的物理原因仍需现场信息 |
| 一次有界恢复 | 8 秒 `SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED)` 请求成功；4 秒、8 秒时仍无 attached display；请求已释放 | 不将临时电源请求成功当成显示器已恢复，不改系统设置或代为解锁 |
| 产品日志 | 新实例没有 Skiko / Direct3D 错误；历史旧实例有 DEVICE_REMOVED 与很晚才发生的崩溃记录 | 历史错误不能证明本次捕获失败的因果，不据此重建或切换 renderer |

证据在本工作树忽略目录 `app-desktop/tmp/workflow-tf03/windows-recovery-20261006T234423/`：`context.json`、`readonly-window-diagnostics.json`、`readonly-session-diagnostics.json`、`readonly-process-sessions.json`、`readonly-display-diagnostics.json`、`window-station.json`、`display-wake.json`。微软定义该查询返回缓冲容量，可能大于实际路径数，故本轮代码不把正容量描述为精确路径数量或原生验收通过，见 [API 说明](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getdisplayconfigbuffersizes)。

已将显示拓扑纳入 Windows 只读预检，与输入桌面和 native-tool 分层。新增 zero / positive / API failure 三项真实 `Preflight.windows` 接线回归：先红后绿，主代理独立复验 3 项通过（0.027 秒）。API 返回失败时保留错误码且不使用输出容量；成功零容量为 `NOT_RUN / NO_ACTIVE_DISPLAY_PATH`；正容量仍不能替代实际捕获/输入。主代理实际运行新预检得到[分层结果](../../app-desktop/tmp/workflow-tf03/windows-recovery-20261006T234423/preflight-with-display.json)：显示拓扑 `NOT_RUN`、正常输入桌面 `PASS`、native-tool `NOT_RUN`，总退出 2，没有误报锁屏或整体通过。已有未受影响的平台测试继续复用。

Windows 剩余出口仍是：恢复有效显示输出后重新选择窗口，实际捕获 → 原生打开入口 → 返回 → 精确关停，并使用第二个新 profile 独立复验。已询问用户当前显示器状态；不能把未收到的回答、脚本测试或 HTTP 正常当作这部分通过，也不在环境未改变时重复捕获、构建或全量测试。

等待显示条件恢复前，主代理重新核对精确 app/profile/参数、launcher/runtime 父子关系及 HTTP 监听者，仅向本次实例请求一次 shutdown（202）。launcher 1480 和 runtime 48848 均自然退出，59653/59654 已释放，未强制终止。当前[Windows 分层结果](../../app-desktop/tmp/workflow-tf03/windows-recovery-20261006T234423/native-result.json)为捕获 `TOOL_FAIL`、原生交互 `NOT_RUN`、隔离关停 `PASS`；没有遗留本次实例，也没有把关停通过算成用户路径通过。

### Windows 物理显示器连接后的双轮收口

2026-10-07，用户确认此前物理显示器处于关屏状态，现在已连接物理显示器并授权继续验收。上节没有现场反馈时的未知物理状态、零容量及捕获失败作为历史记录保留；没有回写为当时已通过。连接后的本次预检成功观察到 `activePathCapacity=1`、`modeCapacity=2` 和正常输入桌面，native-tool 仍为 `NOT_RUN`，实际 CUA 截图与输入另行验证。

继续使用前轮 preview `0.11.19.73.92693c1`，launcher SHA-256 仍为 `32df64c457c21885048d37977e7136da3565cf399da9e531bc46940cbb569d37`。其源码 revision `92693c1a443c8eac7ed3df7ec1bc01b19fc2b6a4`、dirty-at-build 和输入指纹均保留，不称为当前 HEAD 的新构建。没有重建、产品全量或产品代码修改；没有改变系统或应用安全设置。

实施代理完成首验，主代理用另一个全新 profile 独立复验。两轮均由 `Start-Process -WindowStyle Hidden` 启动包装器，应用自行创建真实 GUI；核对 EXE、profile、父子关系、HTTP 监听者与只读 UI PID 后，使用实际 Node `@oai/sky` 的唯一返回窗口进行输入。CUA 分别捕获到真实书架、鼠标打开后的稳定同步面板、Escape 后的稳定书架，HTTP `visible=true→false` 仅作辅助断言。

| 轮次 | 本次精确实例 | 实际原生路径与结果 | 证据 |
|---|---|---|---|
| 首验 | `profile-native-1`；launcher 26680 / runtime 32752；HTTP 59763，JMX 参数 59764；返回窗口 462196 | 从新 1012×762 截图选 `(928,61)`，单次鼠标点击同步入口；返回前实际 foreground PID 32752；单次 Escape；稳定截图确认面板关闭 | [原生报告](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/native-run1.json)、[身份](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/run1-identity.json)、[关停](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/run1-shutdown.json) |
| 主代理独立复验 | `profile-native-2`；launcher 30280 / runtime 37272；HTTP 59773，JMX 参数 59774；返回窗口 1181280 | 重新选择窗口并从新 1012×762 截图选 `(927,62)`；单次鼠标点击；实际 foreground PID 37272；单次 Escape；稳定截图再次确认关闭 | [原生报告](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/native-run2.json)、[关停](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/run2-shutdown.json) |

动作后即时刷新曾显示旧画面或面板进出中的过渡帧。两轮都只重新观察稳定截图，没有重复点击或按键，未把过渡帧误判为事件丢失。报告保存实际窗口与截图观察描述；截图由 CUA 返回并直接观察，没有用 HTTP 状态或其他截图工具替代。上述坐标只属于各自当次截图，后续不得照抄。

两轮均在关停前重新核对本次实例，单次 `/test/shutdown` 返回 202；runtime 和 launcher 均自然退出，没有全局终止或遗留本次实例。可复现的本次准备和核对命令保存在[启动包装](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/launch.ps1)、[身份及正常关停工具](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/session.py)；候选及 profile/端口在[context](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/context.json)，分层元数据在[连接后预检](../../app-desktop/tmp/workflow-tf03/windows-connected-20261007/preflight-before.json)。这些是本轮过程材料；长期操作顺序以 [Test Guide](../automation/TEST_GUIDE.md#windows-原生小场景与正常关停) 为准。

结论限于当前物理显示器连接条件下，空且未连接账号的 profile 的既有同步入口/返回及生命周期重复可用。物理关屏、仅欺骗器/虚拟显示时的成功捕获与输入仍未验证；没有推广为所有显示状态都已修复。preview manifest 的 `nativeInteraction=NOT_RUN` 保持原样，原生通过以独立报告记录，不追认之前失败。

## 收口边界

本轮交付的是可执行流程、隔离体验构建和能力预检，加上明确范围的平台试点。不是稳定版发布，不运行 `finalParityAudit`，不修改产品 roadmap、parity manifest 或其他任务 checkbox。TF01/TF02/TF03 的约定出口均已验证，Windows 连接物理显示器时的双轮原生验收补齐最后缺口，checkoff 与本轮操作规范/证据整合一同提交。通过范围是所记录的三端小场景及隔离生命周期，不是全应用或发布矩阵。Windows 关屏/仅欺骗器、Mac 密码页/安全存储/完整视觉/真实同步，以及 Android 实体升级和 Release/R8，均不在本轮通过结论中。


### 历史阅读器会话流程迁移（2026-10-09）

目标会话 `01a102d8-f600-7c61-a5e0-a214f8954036` 对应 `f235`，迁入前基线 `2eba179e1c`；流程来源 `609f` 的 `17c780adb3`。本次只迁入已有流程、构建入口和验收工具，未启动或恢复目标会话、未执行产品开发、原生验收、完整模块测试或正式构建。19 个文件共同承载同一流程，保留目标历史阅读器接线、专项指南、Android 规则及既有证据，不改产品计划状态。

定向脚本检查：验收预检 22 项通过，Desktop preview 8 项通过/4 项 Mac 专属跳过，Mac runner 19 项通过，目标已有 Mac 构建检查 3 项在 Windows 跳过，共 49 项通过、7 项跳过。这是工具迁移的定向检查，未将跳过项或源工作树历史平台通过结果冒称为目标产品已验收。AppVersion 和历史阅读器证据的未提交改动保持原样；Mac 经验文档中的两段用户原始追加记录保留在工作树、不纳入迁移提交。
