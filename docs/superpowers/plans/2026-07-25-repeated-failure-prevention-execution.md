# 反复失败预防执行计划

日期：2026-07-25  
状态：执行中

## 目标

针对已知的五类高频失败建立可执行保护：范围压缩循环、Gradle 超时后重复启动、子代理完成回执丢失、多权威状态同步和 GUI/系统副作用泄漏。

## Task R1：复用软范围门禁与单一状态权威

- [x] 完成

依赖：

- 治理成本计划 G1、G2。

验证：

- 超限 fixture 为 warning；
- child `active-task` fixture 被拒绝；
- 当前计划状态检查通过。

## Task R2：Gradle 进程生命周期协调器

- [ ] 完成

修改：

- `scripts/gradle-coordinator.py`
- `scripts/tests/gradle-coordinator-test.py`

RED：

1. 写测试启动一个受控慢进程。
2. 第一次等待超时后进程必须继续运行。
3. 第二次调用必须附着原任务，不得启动第二个进程。
4. 最终能够读取原任务退出码和日志。
5. 在实现脚本不存在时运行，确认 RED。

GREEN：

1. `start/run/status/wait/stop` 使用单一状态目录。
2. worker 持有真实子进程并写回终态。
3. 超时只停止等待，不杀进程。
4. stop 只终止记录的进程树。

成功标准：

- 同一协调器 key 同时最多一个运行实例。
- 外层等待超时后不重复启动 Java/Gradle。

## Task R3：子代理完成回执协议

- [ ] 完成

修改：

- `scripts/agent-handoff.py`
- `scripts/tests/agent-handoff-test.py`
- `AGENTS.md`

RED：

1. 缺少 `status/diff/tests/commit/process/next` 任一字段时失败。
2. 合法完成回执通过。
3. 非法状态值失败。
4. 在 validator 不存在时运行，确认 RED。

GREEN：

1. 提供 JSON/stdin validator。
2. AGENTS 规定子代理完成时先发送结构化回执。
3. 主代理先查询状态和进程，再 follow-up；连续两次空闲无回执才允许中断。

成功标准：

- 已完成代理可以只补发摘要，不重新实现。
- 主代理不会把仍运行的 Gradle 误判为代理挂起。

## Task R4：GUI/系统副作用默认隔离

- [ ] 完成

修改：

- 新增统一 `DesktopUrlOpener`
- 迁移已知 UI 中直接 `java.awt.Desktop.browse` 的调用
- 增加行为测试与架构保护

RED：

1. 测试默认 launcher 在 Gradle worker 中不得调用 AWT Desktop。
2. 注入 recording launcher 时精确记录 URL。
3. launcher 失败时返回明确 failure。
4. UI 源目录出现新的直接 `java.awt.Desktop` 依赖时架构测试失败。
5. 先运行新测试，预期因实现缺失 RED。

GREEN：

1. production 真实调用集中在平台 opener。
2. Gradle worker/test mode 默认 fail-closed，不启动浏览器。
3. UI 使用 opener 或已存在的可注入 port。
4. 目录 reveal 继续保持测试 worker 禁用。

成功标准：

- 运行相关 UI/平台测试时不启动浏览器、Explorer 或 Finder。
- 直接在 UI 新增 AWT Desktop 调用会被架构测试拒绝。

## Task R5：组合验证

- [ ] 完成

验证：

- Gradle coordinator tests；
- agent handoff tests；
- `DesktopUrlOpenerTest`；
- `DesktopDirectoryOpenerTest`；
- `DesktopShareServiceTest`；
- `DesktopArchitectureGuardTest`；
- 两份治理 guard；
- `spotlessCheck`。

## 执行结果

待执行后填写。
