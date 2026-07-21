# Comet Design Handoff

- Change: align-sources-extensions
- Phase: design
- Mode: compact
- Context hash: c969b4d74263c9c73ee52acd829086f2afe0e802882dfe8ef451d107f7b7785e

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/align-sources-extensions/proposal.md

- Source: openspec/changes/align-sources-extensions/proposal.md
- Lines: 1-26
- SHA256: ef916afa10811bdb43420d700725e66d11be9420716775ec00b26ab16ea180c5

```md
## Why

Mihon Desktop 的源浏览、扩展发现/安装/更新、安全校验和挑战登录仍存在与 Android 原版并行的业务实现，以及不断扩张的兼容 stub。Desktop 当初为快速交付重写了这些链路；除 ClassLoader、APK→JAR、文件目录和浏览器会话等真实平台差异外，其余状态与规则都应回归原版共享实现。

## What Changes

- 共享源列表、单源浏览、搜索、分页、空状态、错误和偏好 schema。
- 共享扩展发现、版本兼容、安全信任、安装/更新事务及回滚状态。
- 将 Desktop loader/installer 收敛为目录、ClassLoader、APK→JAR 和隔离 side effect。
- 实现 Desktop 浏览器登录与 Cookie 回传；FlareSolverr 只保留为显式可选后备。
- 删除无真实扩展调用证据的 compat stub，以及重复的搜索、版本与错误规则。
- 更新 parity 28–40、87 的实现证据与状态。

## Capabilities

### New Capabilities

- `source-extension-parity`：定义跨端源与扩展状态、安全、安装更新、挑战登录和 Desktop 平台适配边界。

### Modified Capabilities

无既有 OpenSpec capability 的需求发生变化。

## Impact

影响 `source-api`、domain/common、Android/Desktop 源与扩展实现、Desktop 兼容层、i18n、DI、导航、HTTP 集成测试和 Test Mode。不会机械复制 Android PackageInstaller 或 WebView，也不会删除已经有真实扩展和回归测试保护的 Desktop 产品能力。
```

## openspec/changes/align-sources-extensions/design.md

- Source: openspec/changes/align-sources-extensions/design.md
- Lines: 1-35
- SHA256: aa6981a4dd6007213ea200f0e536c33f305da35f4c31ac1e1c8aa188990b24fa

```md
## Context

源与扩展横跨 `source-api`、网络、扩展仓库、安装器、ClassLoader、Android 包管理和 Desktop APK→JAR 兼容层。业务状态和安全规则可以共享，但实际安装、加载和登录挑战必须由平台 adapter 完成。仓库已经有可复用的 `SourceMangaSearchService`、扩展仓库领域层、Desktop 事务替换、APK→JAR、Cookie jar 和 FlareSolverr 后备，不应再建立第二套链路。

## Goals / Non-Goals

**Goals:** 统一源浏览/搜索状态、扩展生命周期和安全规则；保留并验证 Desktop APK→JAR；建立浏览器登录/Cookie 回传；用真实扩展证据收缩 compat stub；让 Android 与 Desktop 消费同一领域结果。

**Non-Goals:** 不复制 Android PackageInstaller/WebView；不承诺支持未通过真实 fixture 验证的扩展 API；不把 FlareSolverr 设为默认业务路径；不在本 change 重做与源/扩展无关的设置或平台集成。

## Decisions

1. 在 common/source-api 中共享源查询状态、分页结果、扩展目录状态、版本、安全、偏好 schema 和错误；平台 adapter 只负责文件、包、ClassLoader 与浏览器 side effect。
2. 源查询以现有 `SourceMangaSearchService` 为唯一请求入口，补充共享分页与错误映射，不复制源调用规则。
3. 安装与更新采用 `prepare → validate → commit → reload → rollback` 事务；只有 reload 成功后才发布 Installed，任一步失败都恢复旧版本与 sidecar。
4. 信任由仓库身份、声明摘要、下载摘要与已安装来源连续性共同决定；缺少可验证摘要时不得把“仓库身份相同”表述成 APK 签名等价。
5. Desktop 登录优先通过可取消、可超时的浏览器会话完成 Cookie 回传；手动导入与 FlareSolverr 是显式后备，取消/超时不得写入半成品凭据。
6. compat stub 使用“真实受支持扩展调用 + 自动化保护测试”白名单；未被引用的 stub 不得扩张，确认无调用后删除。
7. UI 继续使用现有源列表、单源浏览、全局搜索、扩展列表/详情/设置入口，但 ScreenModel 改为消费共享状态；保留宽屏布局、APK→JAR、文件工具和 Test Mode 等 Desktop 产品能力。
8. Android 源/扩展运行时通过自行部署的模拟器安装当前 APK 和代表性扩展验证，避免只从 JVM 测试外推 PackageManager/ClassLoader 行为。

## Risks / Trade-offs

- **第三方扩展行为不可控：** 用代表性 APK/JAR fixture、隔离加载、兼容矩阵与逐项失败反馈限制影响。
- **安全校验与旧扩展兼容冲突：** 明确信任迁移，失败时保留旧版本，不以兼容为由降低校验。
- **浏览器会话无法回传：** 提供取消、超时、手动重试与显式后备，不静默降级。
- **范围较大：** 按契约与 fixture、共享查询、扩展事务、平台登录、UI wiring、去重验收六个可独立审查批次推进。

## Migration Plan

先锁定 Android 权威行为和 Desktop 产品增强，再让新旧 Desktop 路径对同一 fixture 双轨比较；随后依次切换查询 ScreenModel、扩展目录/事务协调器和挑战登录。每个切换点通过测试后删除对应重复规则；安装事务成功前始终保留旧产物。最后更新 parity manifest、执行 Android 模拟器与 Windows/macOS 产品验收。

## Open Questions

代表性扩展集合由当前测试资源、已安装扩展目录和可稳定获取的官方仓库产物共同确定。纯 HTTP 扩展为最低必测集合；QuickJS 或 Android-only AAR 扩展只有在存在 JVM 可用实现时才进入支持矩阵，否则以明确“不兼容”结果结束，而不是扩张无证据 stub。
```

## openspec/changes/align-sources-extensions/tasks.md

- Source: openspec/changes/align-sources-extensions/tasks.md
- Lines: 1-29
- SHA256: cfa812af7862e9743e896dfbf97a3bb737d5018b98f531a0312e6cb59630ef45

```md
## 1. 权威 fixture 与保护网

- [ ] 1.1 盘点 Android/Desktop 源、扩展、compat stub 与真实扩展调用链
- [ ] 1.2 为源分页成功/空/403/429/500/畸形响应写共享与 MockWebServer RED 测试
- [ ] 1.3 为 JAR、APK→JAR、损坏产物、版本替换、回滚与不兼容 API 写 RED 测试
- [ ] 1.4 固定 Desktop APK→JAR、宽屏源 UI、文件工具与现有扩展产品行为基线

## 2. 共享源与扩展核心

- [ ] 2.1 复用并扩展 `SourceMangaSearchService`，提取源列表、浏览、搜索、分页、空状态和错误到共享层
- [ ] 2.2 提取扩展发现、版本、安全、安装/更新事务与回滚状态到共享层
- [ ] 2.3 让 Android 与 Desktop production manager/ScreenModel 消费相同共享状态和错误

## 3. Desktop 平台适配

- [ ] 3.1 将 Desktop loader/installer 收敛为目录、ClassLoader、APK→JAR 与隔离 side effect
- [ ] 3.2 实现仓库身份/摘要信任、下载校验与 reload 失败原子回滚
- [ ] 3.3 实现可取消/超时的 Desktop 浏览器登录与 Cookie 回传；FlareSolverr 仅作显式后备
- [ ] 3.4 将源列表、单源浏览、全局搜索、扩展详情/设置接入共享 ScreenModel 与 UI 状态
- [ ] 3.5 将触达的 Desktop 文案迁入 i18n，并覆盖资源缺 key

## 4. 去重与验证

- [ ] 4.1 依据真实扩展调用证据删除无使用 compat stub，以及重复搜索、版本和错误规则
- [ ] 4.2 更新 parity 28–40、87 和维护文档
- [ ] 4.3 运行 extension/source/network/DI/navigation/Test Mode 与产品回归矩阵
- [ ] 4.4 自行部署 Android 模拟器，安装当前 APK 与代表性扩展并验收源/扩展真实路径
- [ ] 4.5 提交并通过独立规格与代码质量 review
- [ ] 4.6 使用构建脚本和固定 EXE 验收源/扩展关键用户路径，并在 macOS 复核平台适配
```

## openspec/changes/align-sources-extensions/specs/source-extension-parity/spec.md

- Source: openspec/changes/align-sources-extensions/specs/source-extension-parity/spec.md
- Lines: 1-78
- SHA256: acdd6e1fc8f8b78b37b693a75470b9b1b14896fd63f9bb680c3bed006215754b

```md
## ADDED Requirements

### Requirement: Source browsing uses shared state and errors
Android 与 Desktop SHALL 共享源列表、单源浏览、全局搜索、分页、空状态和网络错误语义。

#### Scenario: Source page succeeds or is empty
- **WHEN** 源返回正常列表或空列表
- **THEN** 两端产生相同领域结果，并分别显示内容或明确空状态

#### Scenario: Source response fails
- **WHEN** 源返回 403、429、500 或畸形响应
- **THEN** 两端映射为相同 AppError，并提供与错误类型匹配的重试/登录反馈

#### Scenario: One extension repository fails
- **WHEN** 多仓库刷新中某个仓库失败而其他仓库成功
- **THEN** 系统保留成功仓库的扩展目录，并逐仓库报告失败，不得把部分失败伪装成空列表

### Requirement: Extension lifecycle is transactional and shared
系统 SHALL 共享扩展发现、版本、安全、安装、更新、失败和回滚状态，平台层只执行实际文件/包操作。

#### Scenario: Extension update succeeds
- **WHEN** 新产物通过兼容性、签名/哈希和仓库信任校验
- **THEN** 系统原子替换旧版本并发布共享 Installed 状态

#### Scenario: Extension update fails
- **WHEN** 转换、校验或加载在提交前后失败
- **THEN** 系统恢复可用旧版本并显示逐项错误，不留下半安装目录

#### Scenario: Extension trust does not continue
- **WHEN** 仓库身份、声明摘要、下载摘要或已安装来源连续性校验失败
- **THEN** 系统拒绝静默替换，保留旧版本并显示可审查的信任错误

#### Scenario: Replacement cannot be reloaded
- **WHEN** 新产物已暂存或替换但平台 loader 无法加载代表性 source
- **THEN** 事务恢复旧产物与旧 metadata，重新加载旧版本后才发布失败状态

### Requirement: Desktop keeps evidence-based extension compatibility
Desktop MUST 保留 APK→JAR 和真实扩展所需的兼容接口，但 MUST NOT 添加无调用证据的 compat stub。

#### Scenario: Real extension requires a compatibility API
- **WHEN** 受支持 fixture 在隔离加载时调用兼容 API
- **THEN** 对应 stub/adapter 有调用证据与回归测试

#### Scenario: Compatibility API has no evidence
- **WHEN** 审计找不到真实扩展调用或保护测试
- **THEN** 该 API 不得扩张，并在无调用后删除

### Requirement: Desktop web login returns authenticated session state
Desktop SHALL 提供可取消、可超时的浏览器登录与 Cookie 回传，并只把 FlareSolverr 作为显式后备。

#### Scenario: Browser login succeeds
- **WHEN** 用户完成源登录或挑战
- **THEN** Cookie 安全回传到共享网络会话并重试原请求

#### Scenario: Browser login is cancelled or times out
- **WHEN** 用户取消或流程超时
- **THEN** UI 显示可恢复状态且不写入不完整凭据

### Requirement: Source and extension UI remains usable
Desktop SHALL 提供源列表、浏览、搜索、扩展详情/设置的入口，以及加载、空、错误和权限缺失反馈。

#### Scenario: Required extension or permission is missing
- **WHEN** 用户打开依赖缺失扩展、配置或登录的源
- **THEN** 页面说明缺失项并提供可执行的安装、设置或登录入口

### Requirement: Touched source and extension UI is localized
本 change 触达的 Desktop 源、扩展与挑战登录文案 SHALL 使用共享 i18n 资源，并通过资源完整性测试。

#### Scenario: A supported locale is missing a touched key
- **WHEN** 构建或测试扫描源、扩展与挑战登录所需资源
- **THEN** 缺失 key 会使测试失败，UI 不得退回硬编码业务文案

### Requirement: Android source and extension runtime is emulator verified
涉及 Android 源、扩展加载或安装的变更 MUST 由开发流程自行部署模拟器验证当前 APK 与代表性扩展。

#### Scenario: Shared source or extension wiring changes
- **WHEN** common 状态或 Android adapter 完成测试
- **THEN** 模拟器验证扩展发现/加载、源列表、单源浏览、搜索和失败反馈的真实用户路径
```
