---
parent-plan: docs/superpowers/plans/2026-07-24-task-15-platform-evidence-closure.md
parent-task: Task 153
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: completed
---

# Task 153 installer trust production wiring

本计划只修复 Task 153 已确认的 production wiring 缺口：验证工具能够显式传入受信发布者，
但 Desktop composition root 始终使用空 `InstallerTrust`，使真实应用即使由受控发布流程签名
也只能返回 manual-only。签名、公证、受信 MSI/DMG 和真实用户确认 handoff 仍属于 Task 153
平台验收，本计划不伪造这些外部证据，也不提升 ID 86。

## Task 总览

- [x] Task 153A：build-time installer trust contract
- [x] Task 153B：production DI consumer and governance closeout

### Task 153A build-time installer trust contract

**Risk axis:** immutable-release-trust

**Platform boundary:** build configuration

**Estimated scope:** 3 files, 220 lines

**Files:**
- Modify: `app-desktop/build.gradle.kts`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/update/DesktopUpdateInstaller.kt`

**RED:** 先让 DI test 编译失败于缺失的 BuildInfo trust 常量与不可观察的 installer trust。

**GREEN:** 受控发布流程通过 Gradle properties
`mihonInstallerWindowsPublisher` / `mihonInstallerMacTeamId` 生成不可在运行时覆盖的
BuildInfo 常量；普通开发构建默认空 trust 并诚实保持 manual-only。测试任务获得同源 expected
system properties，验证默认值与显式 release property。

**Security boundary:** publisher / Team ID 不是秘密，但属于签名 trust root；不得从运行时环境、
下载 manifest、更新响应或待验证制品读取。macOS Team ID 非空时必须是 10 位大写字母数字；
生成 Kotlin 字面量必须正确转义。

**Verification:** focused DI test 分别以默认空值和显式测试 trust 运行；Spotless。

### Task 153B production DI consumer and governance closeout

**Risk axis:** installer-trust-production-consumer

**Platform boundary:** Desktop composition root

**Estimated scope:** 4 files, 220 lines

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/di/DesktopAppModule.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- Modify: `docs/superpowers/plans/2026-07-24-task-15-platform-evidence-closure.md`

**RED:** 先断言 production DI 同时绑定 `InstallerTrust` 与 `DesktopUpdateInstaller`，且 installer
持有同一 trust；当前默认构造必须精确失败。

**GREEN:** composition root 从 BuildInfo 构造唯一 `InstallerTrust`，注册到 Injekt，并把同一实例
传给 production installer。ID 86 仍为 `CANDIDATE`；Task 153 仅从“production 永远 manual-only”
缩减为“等待外部签名产物与真实 handoff”。

**Mutation:** 将 installer 改回默认构造或把运行时环境变量作为 trust root，DI/governance contract
必须失败。

**Verification:** default + explicit-property DI tests、installer focused tests、Task15 runner
contract、parity governance、Spotless、plan guard、独立审查和最多一轮修复复审。

## Execution evidence

`task153-installer-trust-red` 先因缺少两个 BuildInfo trust 常量及 installer trust 不可观察而按正确原因失败。
默认空属性与包含引号、反斜杠、`$` 的显式 publisher 加 `TEAMID1234` 分别通过
`task153-installer-trust-green-default`、`task153-installer-trust-green-explicit`；非法
`badteam99` 在配置期按预期失败。`task153-installer-focused` 覆盖 installer 与 process runner，
Task 15 runner contract 在显式选择 Git Bash 后通过，`task153-spotless-check` 通过。只读 diff
自审确认 runtime system properties 不参与 trust root、默认空 trust 仍为 manual-only、DI consumer
持有注册到 Injekt 的同一实例。父 Task 153 与 ID 86 保持未完成/`CANDIDATE`，真实签名产物和
Windows/macOS handoff 仍是外部验收边界。

唯一审查修复轮先由 `task153-inputs-red` 精确证明 `generateBuildInfo` 未声明
`installerWindowsPublisher` 稳定输入；随后该 task 为 Windows publisher 与 macOS Team ID
都声明 `inputs.property`。同一 build directory 的 `task153-inputs-green-empty-1` →
`task153-inputs-green-explicit` → `task153-inputs-green-empty-2` 顺序全部通过，最终生成源码
恢复两个空 trust 常量，未残留显式值；`task153-inputs-di-focused` 继续证明 production DI
consumer 正常。

## 回收条件

两个 Task 均有 RED→GREEN、production wiring、focused test 和独立审查后，本计划可标记
`completed`。这只关闭仓库内 wiring 缺口；Task 153、ID 86 和父 Task 17 在 canonical signed
MSI/DMG、外部受信身份及真实 Windows/macOS handoff 通过前继续保持未勾选。
