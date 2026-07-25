# Task 15 平台证据验收报告

日期：2026-07-26（Asia/Shanghai）

固定原版 provenance：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`

Task 151 验收提交：`631e47d4f101aa7aca9e703acde09360b8185bcd`

提交 tree：`442c84cc2fd1bbb55f0d070e257989caa4b7f20f`

production 输入摘要：`c6baa0a556d4824c4c749817471ffa16d5358926c80a03bd043466c2cdc424be`

## 当前结论

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

当前 WSL2 会话具有 `DISPLAY=:0`、`WAYLAND_DISPLAY=wayland-0` 和 session DBus，`org.freedesktop.secrets` 可响应 Ping；但 `java`、`xdg-open`、`secret-tool` 均缺失。该环境不满足 Task 151 规定的真实 Linux 桌面构建、协议入口和 host-share 验收前置条件。

因此没有启动 Linux 构建，也没有把 WSLg/DBus 的存在外推为 Linux GUI 通过。

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
