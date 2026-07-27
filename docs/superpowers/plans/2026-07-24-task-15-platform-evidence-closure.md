---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 15
task-base: 148594c791c88f45f9412577ad17b0a6b92ac635
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: completed
---

# Task 15 platform evidence closure

## 固定边界

本计划完成 IDs 81、82、83、84、86、92 的 repository-local implementation/evidence
closure；ID 85 的既有 Widget 豁免不在本计划重新裁决。

- 按用户本次范围校正，产品目标为 Android consumer 与 Windows/macOS Desktop。历史文件
  `docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap.md` 保留原文，
  但其中 Linux 相关文字属于早期计划污染，不定义产品平台。
- Windows/macOS 最终真实构建与运行验收只在父计划 Task 19 对同一待验收版本执行最终一次；
  Task 15/17 不重复设置中间 artifact gate，也不把尚未执行的 macOS case 记为通过。
- Linux 仅保留防御性 adapter 与诚实的 fallback/Unsupported 用户反馈，不是发行平台、真实 OS
  验收平台或完成门禁。
- 正式发布证书、publisher、签名/公证、canonical signed MSI/DMG 及真实发布安装交接属于
  release operations，不阻塞本重构。仓库内只要求 production verifier/trust/handoff 行为
  fail closed，并由受控测试产物与 executable fixtures 保护。
- 六项保持 `CANDIDATE`，由 Task 15 标记 `READY_FOR_PROMOTION`；Task 18 执行统一终态提升。

## Task 总览

- [x] Task 151：current commit URI and host share repository closure
- [x] Task 152：credential and capture repository closure
- [x] Task 153：verifier, trust and handoff repository closure

### Task 151 current commit URI and host share repository closure

**Risk axis:** uri-share-production-contract

**Verification:** production URI broker、单实例 ingress、host-share port、fallback feedback 与验证
runner 已闭合；Windows 历史证据保留冷启动/运行中 URI、文字/文件 share PASS，macOS 历史证据
仅保留已实际通过的运行中 URI，不把未执行的冷启动 URI 或 host share 声明为通过。

**Execution evidence:** 提交 `631e47d4f101aa7aca9e703acde09360b8185bcd`、tree
`442c84cc2fd1bbb55f0d070e257989caa4b7f20f` 的 Windows/macOS evidence 产物来自
`scripts/build-desktop.sh evidence`。Windows 三个场景全部通过，macOS 运行中 URI 通过；
其余 macOS GUI case 统一留给 Task 19 的最终一次验收。Linux 路径只验证 fallback/Unsupported
边界，不新增产品平台承诺。

### Task 152 credential and capture repository closure

**Risk axis:** credential-capture-production-contract

**Verification:** production credential backend identity、保存/覆盖/读取/删除语义、窗口隐私
apply/query/clear、fail-closed policy 与 Supported/Limited/Unsupported/Failed 反馈均由真实
production seams 和 executable fixtures 保护。

**Execution evidence:** Windows DPAPI production roundtrip 已通过。Windows capture 在提交
`1d0d7d8f416e27a4399ea2687c6632d0095eb0f9` 的 evidence 产物上 PASS；protected、clear、
feedback 观察绑定报告中的三项精确 hash。尚未执行的 macOS credential/capture 不计为通过，
统一留给 Task 19。Linux 仅保留 credential/capture fallback/Unsupported 边界，不进入剩余任务。

### Task 153 verifier, trust and handoff repository closure

**Risk axis:** installer-production-contract

**Verification:** production verifier/trust/handoff 已覆盖独立 trust identity、current
commit/tree/productSource sidecar、canonical name/hash/size、prepare、取消、显式确认、
handoff 成功/失败和 manual path；缺失或不匹配输入全部 fail closed。

**Execution evidence:** runner 合同与
`docs/superpowers/plans/2026-07-27-task-153-installer-trust-wiring.md` 的 Task 153A/153B
均已完成。release-controlled build-time trust 进入唯一 `InstallerTrust` composition-root
实例，运行时 system properties 不能覆盖；受控测试产物证明 verifier、取消和 handoff 行为。
正式发布证书、publisher、公证、canonical signed MSI/DMG 与真实发布安装交接归
release operations，不是 repository-local closure 或 parity promotion 的 blocker。Linux
只保留 manual fallback/Unsupported 边界。

## 回收结论

Task 151–153 的 repository-local implementation/evidence closure 全部完成。IDs
81、82、83、84、86、92 保持 `CANDIDATE`，`gap=NONE`，进入 Task 18 统一 promotion；
Windows/macOS 最终一次真实构建与运行验收由 Task 19 独占。
