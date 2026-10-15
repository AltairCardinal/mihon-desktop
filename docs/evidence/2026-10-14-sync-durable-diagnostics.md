# 同步持久诊断补强（2026-10-14）

## 范围与基线

本轮按用户要求补充 Android/Desktop 同步诊断。基线为主分支 `21733028bc`，
隔离分支先重放上一轮仅记录最新异常的诊断提交 `30a3249d13`，再用共享持久日志取代它。
本轮没有安装应用、重试用户同步、操作用户数据、创建发布版本或更新未经用户验收的公共功能说明。
旧异常已经丢失的原始信息无法由此次改动追回。

## 实现与审查

- 两端 production DI 注入同一个 profile 内的共享 journal；运行准入/跳过、授权、空间检查、
  实际 HTTP、同步阶段、重试、终态和恢复步骤沿已有生产链路记录。
- 同步开始前的手动暂停、恢复、取消也记录；已知运行的授权/请求记录携带尝试编号和触发来源。
- 每条事件记录当时版本与源码身份，运行/请求使用持久随机盐派生别名。
  快照中的 `journalRunAlias` 可关联现有运行事实；结束 24 小时面板会话不清除持久日志。
- 保存异常类型、cause/suppressed 关系和有界调用栈，不保存异常消息、账号、仓库、作品、
  URL、凭据、请求头或响应体。HTTP 仅保存固定操作类别、网络失败阶段与状态码。
- 最多 256 条、128 KiB，单条最多 16 KiB，优先淘汰成功噪声。导出仍限 256 KiB，
  优先保留 journal、裁剪面板观察；截断、丢弃数与存储错误显式输出。
- 原子替换、有界读取、损坏拒绝、写入失败保留内存记录；日志不参与同步结果或恢复决策。
  本地自动记录不自动上传。详细路径、格式与限制见
  [架构说明](../architecture/sync-diagnostics.md)。

一个复用的实施代理承担共享日志、生产链路及契约测试；主代理独立检查这些变更，
承担双端 DI、诊断采集/导出、复制入口及文案整合。审查修正了有界读取、跨版本环境、
重试关联、诊断读取异常隔离等问题。文件较多是同一链路贯穿共享层、两个平台和现有 UI 的必要接线，
未新增独立同步实现、数据库 schema 或自动重试策略。

## 红绿证据

- 共享持久化红测：重启保留、轮转、故障隔离等缺失时正确失败。
- HTTP/run store/exchange 红测及恢复红测：生产路径未写诊断事件时正确失败。
- 平台红测：Desktop 导出缺少 journal 的断言失败；Android 对应新断言失败。
  初次组合命令使用前导通配符，观察到失败后仍不断启动测试进程。
  为减少其与 Android 每类独立进程配置组合时的额外开销，只停止协调器记录的该次进程树，
  后续改为完整限定类名。此轮不是完整通过证据。
- 补充红测 `sync-journal-boundary-red5`：2 项均因预期缺口失败——
  暂停/恢复/取消事件缺失、授权证据缺少尝试编号。随后最小补齐这两处。
- 格式工具首次暴露的行长问题已修正；工具配置/编译错误不计为行为红测。

## 最终验证

`sync-journal-focused-green` 执行了以下 focused 范围：

| 范围 | 结果 |
| --- | --- |
| JVM `SyncDiagnosticJournalTest` 共享契约 | 5 项通过 |
| `SyncDiagnosticJournalIntegrationTest` | 5 项通过 |
| `SyncDiagnosticJournalRecoveryTest` | 1 项通过，真实 HTTP 500 路径命中断言通过 |
| `SyncDiagnosticStoreTest` | 5 项通过，包含导出裁剪时保留持久失败证据 |
| 既有 `SyncFailureDiagnosticsIntegrationTest` | 7 项通过 |
| 既有 `SyncHttpResponseBoundaryIntegrationTest` | 1 项通过 |
| Desktop production DI → 真实 coordinator 跳过 → 采集 → 导出 | 1 项通过 |
| Android DomainModule / production worker → 采集 → 导出 | 1 项通过 |
| Compose 诊断导出失败 → 复制诊断 → 保留 journal 与原错误 | 1 项通过 |
| 修改文件 Kotlin 格式检查（data/app/presentation-sync）、i18n XML 检查 | 通过 |

该组合命令整体失败于 Android 共享测试夹具使用 SDK 未暴露的 Java `Files.readString/writeString`，
不记为组合命令全部通过。夹具已改为显式 UTF-8 的 `readAllBytes/write`，
随后仅补跑受影响的 JVM/Android 同一共享契约和 data 格式检查；其他代码及通过证据未受影响。
`sync-journal-contract-final` 最终成功：JVM 同一契约 5 项、Android 同一契约 5 项均通过，
data 格式检查通过。按未受测试夹具改动影响的有效基线加局部补验，共 32 项选定用例通过；
不将该组合证据称为最终代码重新通过全量测试。本轮不运行完整模块测试或发布矩阵。
Gradle 的限定重跑会替换同一模块的测试 XML；24 项 JVM 基线在补跑前已逐类读取确认，
随后 XML 仅保留重跑的 5 项共享契约。其余 19 项 JVM 基线复用，测试夹具改动不涉及它们的实现。

复验入口为以下任务和限定测试类；重型 Gradle 均经仓库协调器串行运行：

```text
python scripts/gradle-coordinator.py run --key sync-journal-contract-final -- gradlew.bat
  :data:jvmTest --tests mihon.data.sync.SyncDiagnosticJournalTest
  :data:testReleaseUnitTest --tests mihon.data.sync.AndroidSyncDiagnosticJournalTest
  --max-workers=1 --no-parallel
```

平台入口分别为 `:app-desktop:jvmTest` 限定
`mihon.desktop.sync.DesktopSyncWiringTest.isolated graph factory consumes the exact test target*`，
以及 `:app:testReleaseUnitTest` 限定
`eu.kanade.tachiyomi.data.sync.AndroidSyncRuntimeWiringTest.DomainModule and production worker share the same unconfigured runtime`。
方法名含空格，实际命令中须作为一个加引号的参数传递。
其余最终命令和原始输出在隔离 worktree 的 `.gradle-coordinator/sync-journal-focused-green.{json,log}`；
补验在 `sync-journal-contract-final.{json,log}`。这些是本机过程产物，不是发布产物。

## 验收边界

现有同步面板 → 诊断 → 采集快照 → 保存诊断 JSON / 复制诊断，可带出持久记录。
自动化覆盖真实生产类和平台 DI；Android 使用主机 JVM 契约与 Robolectric 接线，
不是实体设备或 APK 安装验收。Desktop 接线不是打包产物运行验收。
代码进入新构建并安装后，现有设备才会开始使用此次持久记录。
日志是有容量上限的尽力记录，不能保证保留无限历史、进程突然退出前尚未完成落盘的事件，
也不支持同一日志目录被多个进程同时写入。
