# Mihon Desktop 平台对齐集中验收报告

日期：2026-07-22（Asia/Shanghai）
验证基线：`6062ebe8b1e76814bd363cb9a6fb26550d8c7978` 及本报告随附的验收修复
固定原版 provenance：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`
最终 Desktop 版本：`0.11.14.44.6062ebe`

## 结论

Task 5A / Task 16 的集中验证通过。whole-change 独立审查结论为 APPROVED，Critical/Important/Minor `0/0/0`。shared、Android、Desktop 的全量测试矩阵通过；Windows 固定未打包 EXE 与 macOS 部署应用包均来自 BUILD 44，并完成 Test Mode、URI/update 运行时快验。Linux/WSL 只形成 capability 与 production seam 证据，未把缺少 Java、Secret Service 和真实桌面会话的 WSLg 环境误报为完整 Linux GUI 验收。

集中验收发现并修复了四项跨环境问题：macOS 分享快照在内容写入前没有原子收紧权限、headless DI 测试错误期望 native share session、窗口隐私 Flow 测试存在异步竞态、MangaDex 测试的“先探测再绑定”端口存在 TOCTOU 竞争；同时把既有 Architecture Guard 基线更新到已审查实现的真实行数。最终 Windows 构建脚本内置 `jvmTest` 为 2007 tests / 0 failures / 0 errors / 3 skipped。

## 全量命令与结果

除明确注明外，命令在 Windows 仓库根目录串行执行；网络命令按仓库代理规则运行。

| 命令 | 结果 | 测试数 / 失败 / 错误 / 跳过 | 时间与备注 |
|---|---:|---:|---|
| `./gradlew spotlessCheck` | PASS | 不适用 | 初次 28.4s；最终复跑 23.9s |
| `./gradlew :domain:allTests` | PASS | 564 / 0 / 0 / 0 | 首次因未设置 Android SDK 在测试前失败；唯一环境重试设置 `ANDROID_HOME=.android-sdk` 后 7.1s 通过 |
| `./gradlew :data:allTests` | PASS | 40 / 0 / 0 / 0 | 8.2s |
| `./gradlew testReleaseUnitTest` | PASS | 373 / 0 / 0 / 0 | 63.3s |
| `./gradlew :app-desktop:jvmTest` | PASS | 2006 / 0 / 0 / 3 | 初次 2006 / 1 / 0 / 3，Architecture Guard 报告已审查的 `MangaDetailComponents.kt` 710 行超过旧基线 707；更新基线后 84.7s 全绿 |
| `./gradlew :test-desktop:test` | PASS | 17 / 0 / 0 / 0 | 8.4s |
| Windows `scripts/build-desktop.sh` | PASS | 2007 / 0 / 0 / 3 | 最终重建 149.6s；脚本内部按项目规则执行测试并生成固定未打包应用 |
| macOS `scripts/build-desktop.sh full-tests` | PASS | 2011 / 0 / 0 / 3 | 最终验收修复同步后 57s；计数由初次 2010 项加新增“写入前私有权限”回归测试得出 |
| macOS `scripts/build-desktop.sh` | PASS | 内置 Desktop 测试全绿 | 最终构建/部署 90s；先前一次构建由 MangaDex 测试端口 TOCTOU 触发 1 个 `BindException`，修复后未再复现 |

TDD/回归修复证据：

- 分享快照新增测试直接在 `ImageIO.write` 前观察已创建文件；RED 证明旧实现的 POSIX 权限只在创建后补设，存在短暂 `0644` 窗口。GREEN 使用 `PosixFilePermissions.asFileAttribute` 在 `createTempFile` 时原子创建 `0600` 文件，并在写入后再次收紧；非 POSIX 文件系统保留普通创建路径。
- macOS full-tests 的 headless DI RED 实际返回 `Unavailable(HEADLESS)`，测试改为验证无 native exchange、无虚假 terminal callback；GUI 分支继续保护真实 session lifecycle。
- macOS full-tests 暴露窗口隐私偏好 Flow 的调度竞态；测试改为有界等待 production bridge 的调用次数，不改变生产行为。
- macOS 构建中的 MangaDex RED 是 `ServerSocket(0)` 关闭到 Netty 再绑定间的 TOCTOU；五个真实 embedded-server 测试改为让 Netty 绑定 `port = 0`，再读取 `resolvedConnectors()`。
- 修复后 Windows focused 33 / 0 / 0 / 0、macOS focused 33 / 0 / 0 / 0、MangaDex focused 4 / 0 / 0 / 0；最终 `spotlessCheck` 与 `git diff --check` 通过。

## Android 验收

环境：Android Emulator `mihon-api36`，Android 16 / API 36，x86_64，1080×2400，serial `emulator-5554`。仓库 ADB client 41 因 WOMic 占用默认 5037（server 39）而隔离使用 5038；这只影响控制端口，不改变被测 APK。

APK：`app/build/outputs/apk/debug/app-x86_64-debug.apk`，57,887,774 bytes，mtime `2026-07-22 15:12:42`，package `app.mihon.dev`，versionCode 18，versionName `0.19.4-8595`。`assembleDebug` 129.5s 通过，`adb install -r` 成功。

运行时结果：

- 冷启动 `ACTION_SEARCH` 携带唯一文本 `task16-search-evidence`：MainActivity 为 resumed top，真实 EditText 显示查询，无 FATAL。
- `ACTION_SEND text/plain` 携带 `task16-send-evidence`：MainActivity 接收，真实 EditText 显示文本。
- `ACTION_VIEW tachiyomi://add-repo?url=https%3A%2F%2Fexample.org%2Findex.min.json`：显示真实 Add repo 对话框及 Add/Cancel，URL 内容正确。
- 设置 PIN 后，“Require unlock”打开真实 BiometricPrompt（`Require unlock / Authenticate to confirm change`）；ADB 无法向 secure UI 注入并观察错误 PIN，因此没有把认证失败 UI 冒充为手工通过。失败保持锁定、delay `-1/0/>0`、secure screen `ALWAYS/INCOGNITO/NEVER` 由 production seam 测试覆盖：`AndroidSecuritySharedPolicyTest` 4/0/0/0、`AndroidSecuritySettingsWiringTest` 2/0/0/0。
- Secure screen 真实设置页展示 Always / Incognito mode / Never / Cancel。
- updater 兼容性 `AppUpdateCheckerCompatibilityTest` 3/0/0/0；debug APK 环境没有可控制的真实 release 响应，因此 force/no-update/new-update 保留为真实 checker production seam 证据，不外推为网络端到端。
- Android 外部动作 production wiring：`AndroidExternalActionSharedWiringTest` 3/0/0/0。

## Windows 验收

固定产物绝对路径：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`

- 最终 `scripts/build-desktop.sh` 退出码 0；EXE FileVersion/ProductVersion `11.14.44`，mtime `2026-07-22 16:02:57`，size 540,160 bytes。
- 普通 GUI 启动的真实窗口标题为 `Mihon Desktop 0.11.14.44.6062ebe`，进程 responding。
- 固定 EXE Test Mode 返回 `screen=HomeScreen`、`testMode=true`、`appLocked=false`、`updateStatus=idle`；update action 形成 `idle → checking → no_compatible_package`。
- 固定 EXE 二次进程接收 add-repo URI 并正常退出，证明 single-instance broker ingress；Test Mode `/test/state` 不投影对话框，故没有把 API 状态误报为 UI 对话框证据。
- Windows focused（DPAPI、分享、URI、窗口隐私、About/update、Test Mode）50 / 0 / 0 / 0；包含本机真实 DPAPI roundtrip。
- `scripts/desktop-smoke-test.sh` 在 Git Bash 执行 89 / 0 / 0 / 0，85.7s。WSL 包装尝试分别因 `JAVA_HOME` 缺失、wrapper 引号解析、Windows JDK 不能驱动 Linux `gradlew` 在测试前退出，均未算测试失败。
- 所有本轮 Windows 应用进程已终止，Test Mode 端口已释放。

## macOS 验收

环境：`ssh mbp`，macOS 14.8.4。用户仓库 `/Users/altair/Github/mihon` 保持原状；验收在 `/private/tmp/mihon-task16-6062ebe-20260722153247` 的隔离副本进行，基线精确为 `6062ebe` 并同步本轮验收修复。

- 部署产物：`/Applications/Mihon Desktop.app`，版本 `0.11.14.44.6062ebe`；Info.plist short/build `11.14.44`，bundle id `mihon.desktop`，Mach-O x86_64，mtime `2026-07-22T15:53:12+0800`。
- 部署包内可执行文件以 headless Test Mode 启动成功，返回 `HomeScreen`、`testMode=true`；二次执行 add-repo URI 正常退出。
- updater 运行时状态为 `idle → checking → no_compatible_package`。
- macOS focused 七类（credential backend、native share、窗口隐私、About、URI、update）52 / 0 / 0 / 2；跳过项分别是 Windows HWND 测试的平台不适用，以及 SSH session 无法完成真实 macOS Keychain roundtrip。受控 Keychain 命令语义通过，但不会外推成 GUI login-session Keychain 成功。
- `scripts/desktop-smoke-test.sh` 89 / 0 / 0 / 0，82s。脚本初次因隔离副本 executable bit 缺失退出 126；仅修复临时副本权限后重试通过。
- SSH 非交互会话无法操作真实 Share Sheet、GUI 菜单/标题/About 和屏幕捕获，因此这些项以真实 production adapter/生命周期测试作为有限证据，不标记为 OS 人工交互通过。
- 验收进程已停止；用户原仓库未修改。

## Linux / WSL 能力矩阵

环境：Ubuntu 24.04.4，WSL2 kernel 6.6.87.2，WSLg `/mnt/wslg`、DISPLAY/Wayland、DBus session 存在；但 Java、`xdg-open`、`secret-tool` 均缺失，`org.freedesktop.secrets` 返回 `ServiceUnknown`，且无可确认的真实桌面 session。

因此没有执行 Linux GUI 打包、真实 Secret Service credential roundtrip、portal/clipboard 人工交互或 updater handoff。Linux production seam focused（credential capability、URI registration、platform paths、window privacy）27 / 0 / 0 / 0；它证明适配器在支持/不支持状态下的行为，不等价于真实 Linux OS acceptance。Linux updater 维持 `ManualOnly` 边界。

## Parity IDs 81–86、92

| ID | 当前状态 | 本轮证据 | 保留边界 / 偏差 |
|---:|---|---|---|
| 81 外部 URI | CANDIDATE | Android add-repo 真实对话框；Windows/macOS 固定产物二次进程 ingress；broker/runtime 测试 | Desktop 使用本地单实例 broker 与 macOS `APP_OPEN_URI` adapter，不使用 Android Intent；macOS运行中 GUI open-event 未通过 SSH 人工操作 |
| 82 分享 | CANDIDATE | Android `ACTION_SEND` 真实入口；Desktop share production wiring、并发 session、terminal cleanup、私有快照 focused 测试 | Desktop 委托宿主 Share Sheet/clipboard/file fallback；SSH 无真实 Share Sheet 交互。POSIX 快照现从创建瞬间即为 `0600` |
| 83 应用锁 | CANDIDATE | Android 真实 BiometricPrompt 入口；shared policy、Android consumer、Desktop lock/credential/DI 测试；Windows真实 DPAPI roundtrip | Desktop 使用 DPAPI/Keychain/Secret Service/JVM adapter；macOS SSH Keychain 与 Linux Secret Service 不具备完整交互条件 |
| 84 屏幕隐私 | CANDIDATE + PLATFORM-EXEMPT | Android 三态真实设置页；Desktop policy/bridge/状态反馈测试 | OS capture affinity 能力有限，unsupported/failed 必须向用户显示；无 macOS/WSL 真实截图阻断人工证据 |
| 85 Widget 隐私 | EXEMPT | Android Widget production wiring 与 shared Updates 契约由全量矩阵覆盖；Desktop capability 明确 Unsupported | Desktop 没有系统 Widget provider，不创建伪 Widget 抽象；只复用 Updates 数据链 |
| 86 更新 | CANDIDATE | Android checker compatibility；Windows/macOS Test Mode `idle → checking → no_compatible_package`；Desktop download/install/process/state tests | Desktop checksum/signature/size/redirect 是额外安全加固；当前仓库/APK-only release 没有兼容 Desktop package，真实 signed artifact/OS handoff 保持有限 |
| 92 安全与隐私设置 | CANDIDATE | app lock、delay、screen privacy 的 shared/Android/Desktop production wiring；设置反馈与 capability tests | Desktop 原生通知内容与 telemetry runtime 均 Unsupported，不显示欺骗性开关；screen privacy 仍受 OS 能力限制 |

这些状态与 `parity-manifest.json` 一致：没有把 CANDIDATE 升格为完整跨 OS ACCEPTED，也没有把 ID 85 的平台豁免或 ID 92 的 Unsupported 子能力隐藏掉。

## 产物、清理与交付边界

- Desktop BUILD 由 43 通过 `scripts/build-desktop.sh` 递增为 44；未直接调用 Gradle 生成/部署桌面产物。
- Windows 固定 EXE 与 macOS app bundle 版本一致；macOS 构建位于隔离目录，Windows 产物位于仓库 `app-desktop/tmp`。
- AVD、SDK/Gradle cache、临时构建目录和用户已有未提交文件不纳入提交；`AGENTS.md` 与 `DownloadQueueScreen.kt` 的用户改动未触碰。
- 本轮只提交报告、BUILD 元数据和集中验收中由真实 RED 暴露的修复/测试；不修改父子计划勾选、progress、roadmap 或 OpenSpec。
