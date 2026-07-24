---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 15
task-base: 148594c791c88f45f9412577ad17b0a6b92ac635
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: planned
active-task: Task 151
---

# Task 15 platform evidence closure

## 固定边界

本计划只补齐 IDs 81、82、83、84、86、92 缺失的真实 OS 或签名产物验收；ID 85 已有明确批准的 Widget 平台豁免，不在本计划重新裁决。测试 seam、旧构建或非交互 SSH 不能冒充当前提交的 GUI/安装验收。

- Windows 与 macOS 必须从同一待验收提交用 `scripts/build-desktop.sh` 产出并记录版本、绝对路径和 hash；不得直接用 Gradle 构建 Desktop。
- macOS 先使用 `ssh mbp`，连接失败一次才允许改用 `ssh mbp-lan`；在隔离 worktree 验收，不修改远端用户仓库。
- Linux 必须有真实桌面 session、Java、`xdg-open`、Secret Service 与适用 portal；WSL 缺少这些条件时只记录阻塞。
- IDs 81、82、83、84、86、92 没有具体用户豁免批准，不得标记 `EXEMPT`。
- 任一真实 probe 暴露产品缺陷时停止该 Task，先建立独立产品修复计划；本计划不把运行时缺陷改写成“环境限制”。

每个平台的原始命令输出与 runner JSON 写入 `build/task15-platform-evidence/<os>/`（不提交），最终报告记录 `git rev-parse HEAD`、tree、产物绝对路径、SHA-256、版本、时间、case、退出码与可见反馈。只有各 OS 的 `git rev-parse 'HEAD^{tree}'` 相同，且产物 hash 与本轮记录一致时才可合并证据；Windows 用 `Get-FileHash -Algorithm SHA256`，macOS/Linux 用 `shasum -a 256`。若 runner 尚不存在，RED 必须先确认缺失，再把创建 `scripts/task15-platform-evidence-test.ps1`、`scripts/task15-platform-evidence-test.sh` 限定为验证文件；runner 不得绕过 production 入口。

## Task 总览

- [ ] Task 151：current commit URI and host share acceptance
- [ ] Task 152：credential and capture OS matrix
- [ ] Task 153：signed artifact and installer handoff

### Task 151 current commit URI and host share acceptance

**Risk axis:** uri-share-os-acceptance

**Platform boundary:** verification

**Estimated scope:** 5 files, 350 lines

**Verification:** 同一提交的 Windows/macOS 应用包完成冷启动 URI、运行中单实例 URI 与真实 host share；Linux 只在满足桌面前置条件时验收。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Create: `scripts/task15-platform-evidence-test.ps1`
- Create: `scripts/task15-platform-evidence-test.sh`
- Create: `docs/superpowers/reports/<date>-task-15-platform-evidence-verify.md`

**RED:** 平台契约在任一目标缺少 current commit artifact、冷/热 URI 结果、host share terminal result 或用户反馈时保持 IDs 81/82 为 `CANDIDATE`。

**GREEN:** 用构建脚本产出当前提交应用，记录 Windows 协议命令与 macOS bundle URL type；分别验证无运行进程和已有 owner 进程时的 URI 路由，并在真实桌面 session 触发文字与文件分享，观察 `SharedNatively` 或诚实 fallback。

**Repeatable evidence commands:**

```powershell
git rev-parse HEAD
git rev-parse 'HEAD^{tree}'
bash scripts/build-desktop.sh
Get-FileHash -Algorithm SHA256 'app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe'
& scripts/task15-platform-evidence-test.ps1 -Case uri-cold -EvidenceDir build/task15-platform-evidence/windows
& scripts/task15-platform-evidence-test.ps1 -Case uri-running -EvidenceDir build/task15-platform-evidence/windows
& scripts/task15-platform-evidence-test.ps1 -Case host-share -EvidenceDir build/task15-platform-evidence/windows
```

```bash
ssh mbp 'cd <isolated-current-tree> && git rev-parse HEAD && git rev-parse "HEAD^{tree}" && ./scripts/build-desktop.sh'
ssh mbp 'shasum -a 256 "/Applications/Mihon Desktop.app/Contents/MacOS/Mihon Desktop"'
ssh mbp 'cd <isolated-current-tree> && scripts/task15-platform-evidence-test.sh --case uri-cold --evidence-dir build/task15-platform-evidence/macos'
ssh mbp 'cd <isolated-current-tree> && scripts/task15-platform-evidence-test.sh --case uri-running --evidence-dir build/task15-platform-evidence/macos'
ssh mbp 'cd <isolated-current-tree> && scripts/task15-platform-evidence-test.sh --case host-share --evidence-dir build/task15-platform-evidence/macos'
```

Linux 先记录 `command -v java xdg-open` 与 `git rev-parse 'HEAD^{tree}'`，再用 `scripts/build-desktop.sh`、`shasum -a 256 <artifact>` 和同一 shell runner 执行三个 case；缺少真实桌面前置条件时只写 blocked JSON，不记通过。

**Mutation:** 断开 broker 转发或 native share port，验收必须分别失败于 running-open 或 host share，而不是由 parser/service 单测代替。

**User entry:** OS 协议链接；漫画详情与阅读器分享动作。

**Feedback:** 目标页面/安全错误，以及已系统分享、已复制、已保存、取消或失败的区分反馈。

### Task 152 credential and capture OS matrix

**Risk axis:** credential-capture-os-acceptance

**Platform boundary:** verification

**Estimated scope:** 5 files, 350 lines

**Verification:** Windows DPAPI、macOS Keychain、Linux Secret Service 均在真实目标 session 完成保存/覆盖/读取/删除；窗口隐私按 OS 实际能力完成应用、清除和 capture acceptance。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Create: `scripts/task15-platform-evidence-test.ps1`
- Create: `scripts/task15-platform-evidence-test.sh`
- Modify: `docs/superpowers/reports/<date>-task-15-platform-evidence-verify.md`

**RED:** 缺少任一受支持 OS backend roundtrip、真实窗口 handle 或 capture 观察时，IDs 83/84/92 保持 `CANDIDATE`；命令存在或 adapter result 不等价于验收。

**GREEN:** 在当前提交应用的真实用户 session 执行三个 credential backend roundtrip；Windows 查询并验证窗口 affinity，macOS 只声明实际观察到的共享限制，Linux 无统一能力时保持 Unsupported 并验证 UI 反馈。

**Repeatable evidence commands:**

```powershell
& scripts/task15-platform-evidence-test.ps1 -Case credential-roundtrip -EvidenceDir build/task15-platform-evidence/windows
& scripts/task15-platform-evidence-test.ps1 -Case capture -EvidenceDir build/task15-platform-evidence/windows
```

```bash
ssh mbp 'security find-generic-password -s mihon.desktop.task15 -g'
ssh mbp 'cd <isolated-current-tree> && scripts/task15-platform-evidence-test.sh --case credential-roundtrip --evidence-dir build/task15-platform-evidence/macos'
ssh mbp 'cd <isolated-current-tree> && scripts/task15-platform-evidence-test.sh --case capture --evidence-dir build/task15-platform-evidence/macos'
command -v secret-tool
dbus-send --session --dest=org.freedesktop.secrets --type=method_call /org/freedesktop/secrets org.freedesktop.DBus.Peer.Ping
scripts/task15-platform-evidence-test.sh --case credential-roundtrip --evidence-dir build/task15-platform-evidence/linux
scripts/task15-platform-evidence-test.sh --case capture --evidence-dir build/task15-platform-evidence/linux
```

macOS/Linux 的 credential case 必须实际完成保存、覆盖、读取、删除；capture case 必须记录真实窗口、应用/清除结果、截图或录屏观察及 UI 状态。命令缺失、无 GUI/DBus 或 `org.freedesktop.secrets` 不可用时写 blocked JSON。

**Mutation:** 让 credential backend 返回权限拒绝或让 privacy apply/query 不一致，验收必须证明锁 fail closed、设置回滚且限制反馈可见。

**User entry:** More → Security → 应用锁、锁定延迟与屏幕安全。

**Feedback:** 凭据不可用/恢复说明，以及 Supported、Limited、Unsupported、Failed 的准确窗口隐私状态。

### Task 153 signed artifact and installer handoff

**Risk axis:** signed-release-handoff

**Platform boundary:** verification

**Estimated scope:** 5 files, 360 lines

**Verification:** 当前提交的 canonical Windows MSI 与 macOS DMG 具有可验证发布者签名、manifest checksum/size，并在真实 OS 完成用户确认后的 installer handoff；Linux 保持 manual-only。

**Files:**
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Create: `scripts/task15-platform-evidence-test.ps1`
- Create: `scripts/task15-platform-evidence-test.sh`
- Modify: `docs/superpowers/reports/<date>-task-15-platform-evidence-verify.md`

**RED:** 没有受信发布凭据、canonical signed artifact 或真实 handoff 时 ID 86 保持 `CANDIDATE`；JVM fake runner 成功不得提升状态。

**GREEN:** 从受控发布流程获取当前提交 MSI/DMG，验证签名、发布者、SHA-256、size 与 canonical asset name，再由 production installer 在用户确认后交接；取消、校验失败与 handoff 失败不覆盖当前应用并保留 manual path。

**Repeatable evidence commands:**

```powershell
Get-AuthenticodeSignature 'build/task15-platform-evidence/windows/<canonical>.msi' | Format-List *
Get-FileHash -Algorithm SHA256 'build/task15-platform-evidence/windows/<canonical>.msi'
& scripts/task15-platform-evidence-test.ps1 -Case installer-handoff -Artifact 'build/task15-platform-evidence/windows/<canonical>.msi' -EvidenceDir build/task15-platform-evidence/windows
```

```bash
codesign --verify --deep --strict --verbose=2 'build/task15-platform-evidence/macos/<canonical>.app'
spctl -a -vv -t install 'build/task15-platform-evidence/macos/<canonical>.dmg'
shasum -a 256 'build/task15-platform-evidence/macos/<canonical>.dmg'
scripts/task15-platform-evidence-test.sh --case installer-handoff --artifact 'build/task15-platform-evidence/macos/<canonical>.dmg' --evidence-dir build/task15-platform-evidence/macos
scripts/task15-platform-evidence-test.sh --case installer-handoff --evidence-dir build/task15-platform-evidence/linux
```

runner 必须保存用户确认、取消、启动结果和 manual-only 反馈；发布者、checksum、size、canonical name 任一不匹配即失败。真实 probe 若发现 production verifier 或 handoff 缺陷，立即停止并另建 TDD 产品修复计划，本 Task 不预授权任何产品代码或产品测试修改。

**Mutation:** 篡改同尺寸文件、发布者或 canonical 名称，production verifier 必须在启动 installer 前拒绝。

**User entry:** More → About → Check for updates → 下载 → 确认安装。

**Feedback:** 下载/校验进度、取消、无可信产物、安装交接失败和手动更新路径均可见。

## 回收条件

三个 Task 各自完成 TDD、focused tests、真实 OS/产物命令记录和独立审查后，才回到父计划提升对应 ID。若签名凭据或真实桌面环境仍不可用，child 保持 `planned`，相关 ID 保持 `CANDIDATE`，不得用新的 `EXEMPT` 绕过。
