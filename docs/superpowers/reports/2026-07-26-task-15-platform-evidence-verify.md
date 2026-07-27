# Task 15 平台证据验收报告

日期：2026-07-26（Asia/Shanghai）

固定原版 provenance：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`

## 权威校正（2026-07-27）

本文以下命令、hash、PASS/BLOCKED 与环境观察保留为历史执行证据；其中“Task 151–153
未完成”及后续平台 blocker 结论已被当前治理权威取代，不再定义剩余计划。Task 151–153
现按 repository-local implementation/evidence closure 完成，六项保持 `CANDIDATE` 并交
Task 18 promotion。尚未执行的 macOS case 没有被改写为通过，而是与 Windows 一起仅在
Task 19 对同一待验收版本执行最终一次真实构建与运行验收。Linux 只保留防御性
fallback/Unsupported 边界；正式证书、publisher、公证、canonical signed MSI/DMG 与真实发布
安装交接属于 release operations，不阻塞本重构。

Task 151 验收提交：`631e47d4f101aa7aca9e703acde09360b8185bcd`

提交 tree：`442c84cc2fd1bbb55f0d070e257989caa4b7f20f`

production 输入摘要：`c6baa0a556d4824c4c749817471ffa16d5358926c80a03bd043466c2cdc424be`

## 历史执行时点结论

Task 151 尚未完成，checkbox 保持未勾选，IDs 81/82 保持 `CANDIDATE`。

Windows 当前提交产物的冷启动 URI、运行中单实例 URI 和 host share 全部通过。macOS 当前提交产物的运行中单实例 URI 通过；冷启动 URI 的窗口截图和 host share 的 Share Sheet 终态受 SSH 登录审计链的 macOS Accessibility/TCC 权限阻塞，不能用自动化结果冒充真实 GUI 验收。Linux/WSL 缺少计划规定的完整桌面前置条件，没有执行 Linux 构建或 GUI 验收。

该阻塞不是产品成功或产品失败的结论。它只表示现有 SSH 会话无法观察或操作必须由 macOS 辅助功能授权完成的 GUI 环节。所有不依赖该权限的 Task 151 工作已经完成。

## 构建与产物 provenance

Windows 与 macOS 均在隔离工作树从同一提交和同一 tree 使用 `scripts/build-desktop.sh evidence` 构建，没有直接调用 Gradle 生成 Desktop 产物。

| 平台 | 构建结果 | 版本 | 产物路径 | 产物摘要 |
|---|---:|---|---|---|
| Windows | PASS | `0.11.14.46.631e47d` | `D:\Shell\Github\mihon-task151-final\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop` | `2292d604a881590e81f3944874449938bea1caf1ab79b7e8e0ffb9970a759ffe` |
| macOS | PASS | `0.11.14.46.631e47d` | `/Applications/Mihon Desktop.app` | `522e79b53c021363dacd85747bbef1fe2b12ca7a2ac50fdf529f3201b396dcc5` |

Windows 产物为 366 个文件、252,057,076 bytes；EXE SHA-256 为 `f34e4d59c6bcb0b8a98d2b2648866d0697de00d284a8da2542b57795f49a841c`。macOS 产物为 327 个文件、264,092,362 bytes；可执行文件 SHA-256 为 `e9a9c74b1f59f964438d6f1dee86185a6ae17e284cadb55f10b274629dbe2fa7`。

构建脚本内置的 Windows Desktop 测试为 2,198 tests / 0 failures / 0 errors / 3 skipped。Windows 协调器 key 为 `task151-windows-evidence-build-631e`，macOS key 为 `task151-macos-evidence-build-631e`，二者终态均为 `PASSED`。

## Windows Task 151

证据目录：`D:\Shell\Github\mihon-task151-final\build\task15-platform-evidence\windows`

| 场景 | 结果 | 真实运行时证据 |
|---|---:|---|
| `uri-running` | PASS | Windows ShellExecute 启动协议处理进程；既有 owner 保持唯一，dispatch 进程退出码 0；production 终态为 `ExternalActionRejected / ParserRejected` |
| `host-share` | PASS | 文本为 `CopiedToClipboard` 且剪贴板内容一致；文件为 `Saved` 且 PNG 实际存在；`revealSuppressed=true`，未打开资源管理器 |
| `uri-cold` | PASS | 无既有进程时由注册的 production protocol command 冷启动 `Mihon Desktop 0.11.14.46.631e47d`；四张精确窗口截图均显示稳定的“未找到匹配项”反馈 |

冷启动四张截图 SHA-256 均为 `ce8676d1b7d5848c209d99e926a7703feeea79cd257a6afeb4964bfd2d1d7423`。截图前 runner 强制将目标 HWND 置为前台并复核 foreground handle；若被 Codex、资源管理器或其他窗口覆盖，runner 会失败而不是生成可审批截图。

三个场景结束后没有遗留该固定 EXE 的 Mihon 进程。

## macOS Task 151

隔离工作树：`/tmp/mihon-task151-1c4a7c7`

证据目录：`/tmp/mihon-task151-1c4a7c7/build/task15-platform-evidence/macos`

### 已通过

`uri-running` 在当前提交 `631e47d4f` 和当前部署产物上通过：

- 由 macOS LaunchServices 使用 production URI 入口转发；
- owner PID 保持唯一；
- production 终态为 `ExternalActionRejected / ParserRejected`；
- Test Mode state 与截图 API 返回成功；
- runner 结束后没有遗留 Mihon 进程。

### Accessibility/TCC 阻塞

系统 TCC 数据库 `/Library/Application Support/com.apple.TCC/TCC.db` 的当前记录为：

```text
kTCCServiceAccessibility|/usr/libexec/sshd-keygen-wrapper|0
kTCCServiceAccessibility|com.googlecode.iterm2|2
kTCCServiceAccessibility|/Users/altair/.nvm/versions/node/v22.22.1/bin/node|2
kTCCServiceAccessibility|/usr/local/Cellar/node/25.8.1_1/bin/node|2
```

`auth_value=0` 的 `sshd-keygen-wrapper` 是当前 SSH 会话的审计链入口。即使 iTerm2 或单独的 Node 路径已有 `auth_value=2`，从 SSH 启动的自动化进程仍然不受信任。

已取得的只读阻塞证据：

- 冷启动 URI 的 `.mihon-window.applescript` 调用 System Events 时返回 `“osascript”不允许辅助访问 (-25211)`，因此无法读取窗口几何并形成真实截图；
- host share 的文字 Share Sheet 曾返回 production `OpenedNatively`，证明系统分享面板已打开，但自动选择器随后同样返回 `-25211`，无法取得 `SharedNatively` 终态；
- 替代 Node Accessibility helper 在同一 SSH 审计链中报告 `trusted=false`；
- `launchctl asuser` 不能把 SSH 进程切入已授权 GUI audit session，iTerm2 自动化接口也不可用。

后续实验没有改入 production 或 runner，相关临时 helper 不属于提交。由于权限状态未改变，没有在当前提交上重复执行必然命中同一 TCC 拒绝的 `host-share` 和 `uri-cold`；旧失败证据只用于证明环境阻塞，不作为当前提交的产品失败结论。

完成 Task 151 的 macOS 剩余条件是：在一个对实际自动化宿主授予 Accessibility 的登录 GUI audit session 中，对当前提交产物重新执行 `uri-cold` 与 `host-share`，并取得真实窗口截图和 Share Sheet terminal result。

## Linux / WSL 前置条件

当前 WSL2 会话具有 `DISPLAY=:0`、`WAYLAND_DISPLAY=wayland-0` 和 session DBus。2026-07-27 已安装 OpenJDK 17、`xdg-open`、`secret-tool`、`xdotool` 与 ImageMagick，并启动 `gnome-keyring-daemon` 的 secrets component；但有界 `secret-tool store` 仍因当前 session 没有可非交互创建/解锁的默认 collection 而超时。旧的无回复 `dbus-send` Ping 只能证明消息已发送，不能证明 Secret Service roundtrip 可用。

更早的仓库前置同样阻止验收：在排除 Windows interop 后，`MIHON_HOST_OS=Linux scripts/build-desktop.sh evidence` 以退出码 1 明确报告 `Linux desktop packaging is not configured for this repository`；保留 `powershell.exe` 时只会转去构建 Windows。没有统一构建脚本产出的 Linux 当前提交应用，因此没有绕过脚本直接调用 Gradle，也没有把 WSLg/DBus 或已安装工具外推为 Linux GUI 通过。

## Task 152：credential 与 capture OS matrix

Task 152 验收提交为 `056dcb79db869b4246bda74c937ac3e6c0e08d79`，tree 为 `e5d1466fd252c997aecabca72f206be714adbb9b`，production 输入摘要为 `e18589c4e42c756a220ba3af1695cdfe92d2ea1cf58caddf7ba5db14a29672be`。

| 平台 | 构建结果 | 版本 | 产物 tree SHA-256 | 结果 |
|---|---:|---|---|---|
| Windows | PASS | `0.11.14.47.056dcb7` | `3b50a0c45a786e25d1867f169403e8c5ce6d304fd762236b82ea1bc80a33fa75` | credential PASS；capture BLOCKED |
| macOS | PASS | `0.11.14.47.056dcb7` | `2e68c68a6517ffaef20e0d4cd4349d679305813a8c869e75902ad1b1cd798b56` | credential BLOCKED；capture BLOCKED |
| Linux / WSL | 未启动 | — | — | 前置条件 BLOCKED |

Windows 产物含 366 个文件、共 252,057,078 bytes，EXE SHA-256 为 `4aabd2d7b94e428294a0c0a7d00b6389320077fc9fbe34e6d9335a45d23d7f32`。macOS 产物含 327 个文件、共 264,092,365 bytes，可执行文件 SHA-256 为 `e9a9c74b1f59f964438d6f1dee86185a6ae17e284cadb55f10b274629dbe2fa7`。Windows 协调器 key 为 `task152-windows-evidence-build-056d`，macOS key 为 `task152-macos-evidence-build-056d`，二者终态均为 `PASSED`；验收结束后没有重复或残留的 Gradle 进程。

### Windows

- `credential-roundtrip`：PASS。真实 production identity 为 `DesktopCredentialStore(backend=OsCredentialBackend)` 与 `OsCredentialBackend(platform=WINDOWS)`；服务名为 `mihon-desktop-tracker`，保存、读取、覆盖、再读取、删除与删除后缺失六项均为 true。秘密值没有进入 argv、JSON 或日志。
- `capture`：PASS（2026-07-27 独立重验）。验收提交为 `1d0d7d8f416e27a4399ea2687c6632d0095eb0f9`，tree 为 `54e129eac0f3d236a301779912ce45b138413aba`，版本为 `0.11.14.49.1d0d7d8`；`scripts/build-desktop.sh evidence` 由协调器 key `task152-windows-evidence-build-49-topmost` 执行并终态 `PASSED`。产物 tree SHA-256 为 `a8848ca2c9bcd68801414b89d6d16b6d5bc8c33afac564a660533eab364aec57`，EXE SHA-256 为 `f9f159940d02e548ec9cf418c686e72b483dbf76c5bdeff48d17fac07ea04ed4`，production 输入摘要为 `38d54c17918da268286d09978568aea81f77e99a19e83ee7ef52add00413aa6f`。真实 adapter 的 attach/apply/query/clear 均报告 `Supported`，affinity 应用值为 `17`、清除后为 `0`。
- capture review 与精确截图 hash 绑定：protected `0f8a8252dfab9d3f1485b2fa438234adffca5995b1489f1924d1a3e2b705b8b1` 显示 Mihon 被排除；clear `542fd5579f6601f97fbe1d8eb648ef8fcf7f9f2e59a6fa4336985c6ac66ae8e8` 显示 `Mihon Desktop 0.11.14.49.1d0d7d8` 隐私页；feedback `2e6d5ce438a05238c77f8dc0078202b0a8ee935395a62f9970faba3b436926ca` 显示同一隐私设置及明确的屏幕保护反馈。策略校验器接受观察值 `MihonExcluded / MihonVisible / Supported` 并将 `capture.json` 写为 `PASS`。

历史 credential 证据位于 `D:\Shell\Github\mihon-task151-final\build\task15-platform-evidence\windows`；当前 capture 证据位于 `D:\Shell\Github\mihon-task152-windows\build\task15-platform-evidence\windows`。

### macOS

- `credential-roundtrip`：BLOCKED。production roundtrip 从 SSH 会话进入用户 login keychain，但 `security show-keychain-info "$HOME/Library/Keychains/login.keychain-db"` 明确返回 `User interaction is not allowed.`，因此没有把 Keychain 命令可达外推为保存/覆盖/读取/删除通过。
- `capture`：BLOCKED。带正确 JDK 的 production `DesktopWindowPrivacy` Java 探针实际返回 `{"status":"FAILED","errorClass":"java.lang.IllegalArgumentException"}`；该失败发生在形成可信窗口和截图观察之前，现有 SSH audit chain 又仍被 Accessibility/TCC 拒绝，故既不声明 Supported，也不把它改写成产品缺陷结论。

原始证据位于 `/tmp/mihon-task151-1c4a7c7/build/task15-platform-evidence/macos`。

### Linux / WSL

WSL 具有 `DISPLAY=:0`、`WAYLAND_DISPLAY=wayland-0`、session DBus；OpenJDK 17、`xdg-open`、`secret-tool`、`xdotool` 与 ImageMagick 已于 2026-07-27 安装。`gnome-keyring-daemon` 虽已运行，默认 Secret Service collection 仍需要当前 GUI session 的创建/解锁交互，有界 store 超时，未形成 credential roundtrip。仓库构建脚本又明确拒绝 Linux packaging，无法生成可绑定当前提交 provenance 的 Linux 应用。因而 credential/capture runner 均未启动，工具存在不计为通过。

### Task 152 审查与状态

- 首轮独立审查发现 capture 自我声明、macOS/Linux 静态写死 Unsupported、credential backend 自我声明三个高优先级问题；修复后 runner 改为 production adapter/backend identity，并要求精确截图 hash 与人工 review。
- 修复复审发现 Bash `set -u` 下 `capture_native_window` 同一 `local` 声明引用新变量的问题；主代理最小修复后，直接执行抽取出的真实函数 fixture，联合 runner gate、PowerShell parser、`bash -n`、Python compile 与 `git diff --check` 均通过。由于约定的一轮修复复审预算已经用完，Task 152 没有获得额外独立批准，保持未完成。
- 2026-07-27 的 Windows capture 修复又经过一轮独立审查和一轮修复复审；固定 HWND lease、每次 `SetWindowPos` 前复验 owner PID、异常解除置顶、旧 URI 调用方返回类型以及 `KeepTopmost` 失败清理均由行为 fixture 覆盖。复审末次发现的两个调用方/fixture 缺口由主代理修正后 focused runner gate 通过；未增加第三轮独立审查。
- Windows credential/capture 已各自通过；Task 152 checkbox 仍不勾选，IDs 83、84、92 保持 `CANDIDATE`，因为 macOS 已按用户指令延后且 Linux 仍缺真实桌面前置。延期不是 `VERIFIED` 或 `EXEMPT`。

## Task 153：signed artifact 与 installer handoff

### 当前产物与 production 边界

- Windows 隔离验收树没有 MSI。主工作树构建目录中存在 `app-desktop/tmp/mihon-dist/main/msi/Mihon Desktop-1.11.14.msi`，但 `Get-AuthenticodeSignature` 返回 `NotSigned`，且没有能绑定当前 commit/tree/productSource 的 Task 153 installer provenance sidecar；它不构成本轮签名产物证据。
- 2026-07-27 在 Windows 重新核对发布前置：Windows SDK x64 `signtool.exe` 存在；`Cert:\LocalMachine\My` 没有发布证书，`Cert:\CurrentUser\My` 唯一带私钥证书为 `CN=localhost` 且仅含 Server Authentication EKU；环境也没有 release-controlled publisher/trust 输入。临时自签或把 localhost 证书加入信任不能构成 canonical Mihon 发布身份，因此没有启动必然产出不合格 unsigned/ad-hoc MSI 的构建。
- macOS 隔离树及 `/tmp/mihon-dist` 没有 DMG。`/Applications/Mihon Desktop.app` 被 `/usr/bin/codesign` 判定为 `code object is not signed at all`，`spctl` 返回 rejected，来源为 `no usable signature`。
- production `DesktopAppModule` 以默认空 `InstallerTrust` 创建 `DesktopUpdateInstaller`；即使文件名、checksum 与 size 正确，当前真实安装准备也只能诚实返回 manual-only，不能执行可信 handoff。

因此没有启动 MSI/DMG，也没有把无签名包、已安装 app、JVM fake launcher 或手写 JSON 冒充真实安装交接。ID 86 保持 `CANDIDATE`。

### RED→GREEN 与审查证据

- RED 首先精确失败于 Windows/Unix runner 均未暴露 `installer-handoff`。
- GREEN 增加的合同要求 installer artifact 具有独立 sidecar，并绑定当前 commit、tree、productSource、canonical name、SHA-256 与 size；Windows signer Subject、macOS DMG 自身 Team ID 必须分别与 artifact 外部提供的受信 publisher/Team ID 精确一致。
- 生成的 production probe 以显式 `InstallerTrust` 调用真实 `DesktopUpdateInstaller.prepare()`，先验证 `handoff(false) == InstallCancelled`，再仅在 runner 显式确认时调用 `handoff(true)`；缺少显式确认、可信身份、签名、公证、sidecar 或真实启动结果均只能 `BLOCKED`。
- 首轮独立审查提出三项 P1：未执行 handoff、签名身份从 artifact 自举、provenance/production 字段可自报。唯一修复用普通文本 MSI、第三方/缺失 trust、伪 production 字段、错误/缺失 sidecar 与实际 runner 旁路 fixture 将三项全部关闭。
- 修复复审确认三项 P1 已关闭，但发现 Unix 成功分支仍无条件 `return 1`。该单行控制流问题由主代理直接修正，并补充抽取真实 `installer_result_passed` 的行为 fixture；PASS 必须返回 0，BLOCKED 必须返回非零。没有再增加独立审查轮次，Task 153 因真实签名材料仍缺失而保持未完成。

最终 focused runner contract 为 PASS（36.4 秒）；PowerShell parser、`bash -n`、Python compile 与 `git diff --check` 全部通过。没有运行 Gradle、重新构建、安装器或会改变系统状态的命令。

## 脚本与审查证据

- `scripts/tests/task15-platform-evidence-runner-test.ps1`：PASS。
- PowerShell parser 与 `git diff --check`：PASS。
- runner 验证 current commit/tree、production 输入摘要、构建版本分配和产物摘要；不允许旧产物或测试源变化冒充当前 production 证据。
- 独立审查要求精确锁定 Windows Save 对话框和截图目标；修复后 host share 通过，冷启动截图均为目标 Mihon HWND。Task 151 因 macOS 外部权限仍未满足，未进行完成 checkoff。

## 状态边界与下一步

- ID 81 外部 URI：Windows 冷/热 URI 与 macOS热 URI 已通过；macOS 冷 URI 仍缺 Accessibility 允许的真实窗口证据，保持 `CANDIDATE`。
- ID 82 分享：Windows 文字/文件 fallback 已通过；macOS Share Sheet 缺 terminal result，保持 `CANDIDATE`。
- Task 151 checkbox 保持未勾选。
- 不依赖该 macOS TCC 条件的后续 Task 可以继续施工；不得反复重跑相同的 SSH GUI 失败路径。
