# Computer Use 集中验收与主动结束会话实测

迁入说明（2026-10-09）：本文保留源 `609f` 工作树的流程与历史验收记录，文中的过程目录相对于 `D:/Codex/worktrees/609f/mihon/`。迁入 `f235` 不代表目标历史阅读器已重新验收，不改变目标产品计划的完成状态；开发由用户在原会话启动。

日期：2026-10-07。范围：本地 `609f` worktree，流程基线 `2012f85f2c`；只验证 Computer Use 工作方式及退出，不运行产品全量、Gradle 或重新构建，不迁移其他分支。

**最新结论：主动释放未通过。** 第二轮同一轮对照中，用户明确反馈控制提示直到根会话最终回复出现才消失。`js_reset` 仅证实执行端退出，不能作为轮次中途释放桌面的办法；日常流程已改为原生验收集中在本轮末尾，短收尾后立即 `final`。以下首轮事实保留，不补认其桌面释放通过。

## 核查依据

- 本机 Computer Use 插件 `26.803.41515` 的 `SKILL.md`、`docs/guidance.md`、`docs/api.md` 和 confirmations；实际 `cua_node` runtime 标识为 `2c9e75c4e9c71beb`，`@oai/sky` 版本 `0.7.6`。
- `@oai/sky/dist/project/cua/sky_js/src/targets/windows/internal/computer_use_client_base.d.ts:15` 声明内部原型方法 `close()`；`src/service.js` 只通过 `Object.keys(client)` 注册、执行自有方法。正常 `sky` 代理不公开该方法；没有调用内部接口或自行创建管道客户端。
- `node_repl.js_reset` 工具明确支持重置当前 JavaScript 内核。worker 源码中的 EOF 退出逻辑只作为诊断线索，不作为宿主已经释放桌面控制的证据。
- [官方 Computer Use 说明](https://learn.chatgpt.com/docs/computer-use#windows-foreground-use)描述 Windows 使用前台桌面、用户可以停止任务或接管；没有说明本插件的 agent 退出接口。API 平台的浏览器 session 删除或其他平台的 `stop()` 不适用于这里。

## 首轮本机实验与结果

所有准备先于 Computer Use：核对旧候选及 SHA-256、阅读工具规范、创建独立 profile/端口、准备非交互关停命令。候选为既有 preview `0.11.19.73.92693c1`，launcher SHA-256 `32df64c457c21885048d37977e7136da3565cf399da9e531bc46940cbb569d37`。它只用于工具/流程验收，不代表本轮源码的新构建或发布证据。

| 验收项 | 观察及结论 |
|---|---|
| 开始告知与范围 | 开始前说明目标、操作路径和约 2–3 分钟占用；所有原生动作由主代理集中执行，资料核查代理不操作桌面。 |
| 隔离目标 | fresh `profile-native-1`；launcher PID `39796`、runtime PID `16772`；HTTP `59883`、JMX 声明端口 `59884`。路径、参数、父子关系及 HTTP 监听者匹配；Node cwd 为 `609f`，导入前 `skyAlreadyLoaded=false`。 |
| 集中原生操作 | 09:45:29.186Z 首次枚举，选择精确候选返回的 `process:<EXE>` 窗口 `3277178`；书架截图 → 单次同步入口点击 → 稳定同步面板截图 → 核对前台 PID `16772` → 单次 Escape → 稳定书架截图。最后观察 09:46:37.133Z，连续约 68 秒。 |
| 逐动作观察 | 两次输入后的即时截图均遇到过渡帧，各补一次观察即确认稳定结果，没有重复输入，也没有夹入编译、修复或长等待。 |
| 结束执行进程 | 最后观察后调用公开 `mcp__node_repl__js_reset({})`，返回 `js kernel reset`；09:46:50.176Z 核对本任务 kernel `21004`、trusted-worker `41176` 均已退出。此项 PASS 仅指进程退出。 |
| 保留应用对照 | reset 后 Mihon runtime `16772`、launcher `39796` 仍运行，独立 HTTP 身份和 UI 检查通过；因此内核退出不是应用关闭造成。PASS。 |
| 其他会话隔离 | 另一 worktree 的 kernel `42064`、worker `47512` 保持运行；没有对其发命令或重置。PASS。 |
| 及时停止与关停 | reset 后立即报告已停止 GUI 调用，未重新导入 `sky` 或捕获窗口。仅通过精确实例的 `/test/shutdown` 关停，HTTP 202，两个应用进程自然退出，端口 `59883/59884` 释放。PASS。 |
| 桌面实际恢复 | 首轮没有获得当时的现场反馈，保持未确认；后续另做下节同一轮对照，不追认首轮通过。 |

补查时 Node REPL 宿主仍在运行，启动参数没有日志入口；直接关联的本地 Computer Use 目录没有可读的连接关闭记录。宿主存活不代表控制仍占用，也不代表已释放。本轮没有 native pipe 关闭或桌面控制提示消失的直接证据，该层保持未确认；不将源码中“EOF 导致 worker 退出”反向推导为宿主已经完成清理。

原始过程数据位于本机忽略目录 `app-desktop/tmp/workflow-tf03/cua-lifecycle-20261007/`：`context.json`、`preflight.json`、`node-baseline.json`、`run1-inspect.json`、`reset-result.json`、`run1-shutdown.json`、`final-cleanup.json`。截图已在本次工具结果中观察，未为检查重复解码或导出。

## 第二轮同一轮现场对照

用户明确表示已就位。根会话在重置后继续运行，并保留测试应用，避免将回复结束或应用关闭混作 reset 生效。

| 时间（UTC）/阶段 | 事实 |
|---|---|
| 14:31:03.599Z 开始 | 全新 `profile-native-2`，HTTP `59893`、JMX 声明端口 `59894`；launcher `7080`、runtime `48692`；精确候选窗口 `21237264`。Node kernel `33088`、trusted-worker `33740` 属于本任务，导入前未加载 `sky`。 |
| 原生路径 | 首次点击报 `unknown screenshotId screenshot-0`；重新选择精确窗口并刷新后，一次重试成功。稳定同步面板截图 → 前台 PID `48692` 核对 → 单次 Escape → 稳定书架截图。按既有有界恢复收口，没有扩大测试。 |
| 14:32:32.772Z | 最后一次原生观察。此后没有重新导入 `sky`、枚举窗口、截图或发送原生输入。 |
| 14:32:48.386Z | 公开 reset 返回成功，本任务两个 Node 执行进程退出；Mihon 两个进程仍运行，其他会话 `36452/40952` 保留。 |
| 保持本轮观察 | 告知用户观察约 30 秒并正常使用键鼠，不要主动停止或关闭 Codex。14:34:37.498Z 再核对时本任务 Node 进程仍未出现，应用 HTTP 身份检查正常；根会话仍在运行。 |
| 14:39:45.232Z 清理核对 | `/test/shutdown` 返回 202，Mihon 两个进程自然退出，`59893/59894` 端口释放；其他会话进程保留。根会话此后才输出最终回复。 |
| 用户反馈 | 对提示消失时机的提问，用户明确回复：“本条回复出现才消失”。因此 reset 以及关闭测试应用均未实现本次所要求的提前消除控制提示。 |

结论：执行进程清理、测试实例隔离关停通过；以 reset 实现根轮次中途释放的验收失败。最终回复出现是本次观察到的提示消失时点，尚未直接确认内部 native pipe/控制租约的具体实现。用户没有单独描述键鼠是否被系统拦截，不把提示存在进一步推断为输入锁死。

第二轮过程材料仍在同一忽略目录：`run2-node-baseline.json`、`run2-inspect.json`、`run2-reset-result.json`、`run2-observation-check.json`、`run2-shutdown.json`、`run2-final-cleanup.json`、`run2-confirmation.json`。观察期间保留的 `pending` 是当时状态；随后反馈在独立 `run2-user-feedback.json` 中保存，不回写原始观察。

## 边界与流程修正

前轮将 reset 列为日常退出步骤时，仅有执行进程证据，缺少用户桌面层验证，依据不足。本轮撤销这一推荐：准备、构建、审查和分析前置，Computer Use 集中在本轮末尾；结束后仅做必要关停、简短证据落盘及已准备好的提交，立即输出最终回复。需要离线修复时先结束本轮，诚实说明未完成项，不让用户为维持同一轮开发继续等待。

只读核查没有找到受支持的 agent 中途释放接口。内部 transport 的 `end_turn` 随轮次元数据变化发送，结束事件注册属于收到结束后的回调，二者都不是公开的主动退出 API。子代理结束能否释放根会话控制没有文档保证或实测证据，不另起此实验或将其写成解决方案。

本轮修正的是项目流程，未修改插件或宿主，也未解决“同一根轮次继续运行时主动释放”的工具能力缺口。没有人为制造强制中止或平台异常；其他宿主版本和 Mac/Android 的退出不能据此标记为已实测。默认不把日常收尾变成每次都要用户确认的门禁；若版本变化或提示异常残留，再做针对性核查。
