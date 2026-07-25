# 反复失败预防执行计划

日期：2026-07-25
状态：已完成

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

- [x] 完成

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

- [x] 完成

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

- [x] 完成

修改：

- 新增统一 `DesktopExternalActionPolicy` 与 `DesktopUrlOpener`
- 迁移已知 UI 和网络登录 adapter 中直接 `java.awt.Desktop.browse` 的调用
- 将目录 reveal、分享回退与 URL 打开统一接入自动化测试隔离策略
- 增加行为测试与架构保护

RED：

1. 测试默认 launcher 在 Gradle worker 中不得调用 AWT Desktop。
2. 注入 recording launcher 时精确记录 URL。
3. launcher 失败时返回明确 failure。
4. Desktop production 在平台 adapter 外出现新的直接 browser launch 时架构测试失败。
5. 先运行新测试，预期因实现缺失 RED。

GREEN：

1. production 真实调用集中在平台 opener。
2. Gradle worker/test mode 默认 fail-closed，不启动浏览器。
3. UI 与网络登录使用 opener 或已存在的可注入 port。
4. 目录 reveal 与分享回退同时识别 Gradle worker 和应用 Test Mode。

成功标准：

- 运行相关 UI/平台测试时不启动浏览器、Explorer 或 Finder。
- 直接在平台 adapter 外新增 browser launch 会被架构测试拒绝。

## Task R5：组合验证

- [x] 完成

验证：

- Gradle coordinator tests；
- agent handoff tests；
- `DesktopUrlOpenerTest`；
- `DesktopDirectoryOpenerTest`；
- `DesktopShareServiceTest`；
- `DesktopBrowserLoginAdapterTest`；
- `DesktopArchitectureGuardTest`；
- 两份治理 guard；
- `spotlessCheck`。

## 执行结果

### R1

- 复用方案一的 GREEN：范围 guard `27/27`，超出 8 files/400 lines 只输出 warning；roadmap fixture `3/3`，真实修正版父 roadmap、最终审计计划和五份 child plan 检查 PASS。
- child plan 不再保存 `active-task`；唯一活动任务权威为最终审计计划的 `active-task: Task 17`。

### R2

- RED：协调器尚不存在时，超时附着与失败退出码两个测试均失败；补充 `run` 合同后又精确失败于 argparse 不认识 `run`。
- GREEN：`gradle-coordinator-test.py` 为 `3/3`。慢任务第一次等待返回超时后继续运行，第二次 start 返回 `ATTACHED`，计数器最终仍为 `1`；失败任务保留真实退出码 `7`；`run` 同时返回 start 与终态。
- 首轮 GREEN 暴露 Windows 上 `os.kill(pid, 0)` 不能可靠区分已退出进程，已改用 `OpenProcess/GetExitCodeProcess` 后转绿。
- 真实 Gradle 验证由协调器运行：focused 任务记录 `workerPid=4688`、`processPid=39840`、退出码 `0`；完整套件第一次 50 秒 wait 超时后仍保持同一 `workerPid=45712`、`processPid=9124`，没有重启第二份 Gradle。

### R2 可靠性复审补强

- 触发原因：真实 `spotlessCheck` 已成功退出，但 worker 未写回终态，状态长期停在
  `RUNNING`；首轮补强虽然能回收为 `ORPHANED`，独立复审仍发现锁回收 TOCTOU、
  macOS 秒级身份、旧 PID-only 锁和错误身份 stop 覆盖缺口。
- RED：旧实现下，陈旧锁内容测试和两个并发回收者测试均因锁文件被删除而精确失败；
  ACTIVE 状态的错误进程身份测试改为先执行 `stop`，确保真实进入停止分支。
- GREEN：锁改为 Windows `msvcrt.locking` / POSIX `fcntl.flock`，锁文件永久保留，
  进程退出由操作系统自动释放锁，不再读取、信任或删除旧 PID-only 锁。
- GREEN：Windows 使用进程创建 ticks，Linux 使用 boot ID + `/proc` start ticks，
  macOS 使用 `libproc.proc_pidinfo(PROC_PIDTBSDINFO)` 的秒和微秒创建时间；其他无法
  获得可靠身份的平台 fail closed。
- 自动化结果：`gradle-coordinator-test.py` `8/8`，覆盖等待超时后附着、真实失败码、
  worker 丢失后的 ORPHANED、错误身份不附着且不误杀、陈旧锁内容、双并发回收和锁文件持久化；
  `py_compile` 通过。
- 确定性竞争证据：持锁者先锁定并把锁文件截断为零；竞争者通过 `waiter-ready`
  屏障证明已经到达锁尝试点，首次非阻塞加锁必须返回失败，释放后由同一进程取得锁并
  完成 ORPHANED 125 回收。旧实现在 Windows 精确抛出 `PermissionError(13)`；
  删除加锁前的 sentinel 写入后该场景与完整 `8/8` 均转绿。
- Windows 真实 Gradle：协调器运行 `gradlew help --offline --no-daemon`，
  `workerPid=18404`、`processPid=25920`、退出码 `0`、`BUILD SUCCESSFUL in 33s`，
  终态为 `PASSED` 且锁文件仍存在。
- macOS 真实验证（`ssh mbp`）：同一进程身份稳定且不同进程身份不同，
  格式为 `darwin:<seconds>:<microseconds>`；两个进程竞争同一锁时第二个实际等待
  `0.624s`，进程退出后自动获得锁且锁文件仍存在。

### R3

- RED：validator 不存在时，合法回执、缺字段和非法状态三类测试均失败。
- GREEN：`agent-handoff-test.py` 为 `3/3`；六个必填字段逐项缺失都会失败，合法 `IMPLEMENTED` 回执通过，非法 status 与 `RUNNING` 无 PID 均被拒绝。
- `AGENTS.md` 已固定“先回执、后结束；先查状态/PID、后 follow-up；两次空闲无回执才中断；恢复时复用 diff/tests/process”的协议。

### R4

- RED：`DesktopUrlOpenerTest` 首轮因 production opener 缺失而编译失败；共享外部动作策略首轮因 `DesktopExternalActionPolicy` 缺失而编译失败。
- RED：将架构保护扩大到全部 Desktop production 后，精确捕获 `DesktopBrowserLoginAdapter.kt` 的 `Desktop.Action.BROWSE` 与 `desktop.browse(uri)` 两处绕行。
- GREEN：`DesktopUrlOpenerTest` `4/4`，覆盖 Gradle worker、应用 Test Mode、注入 recorder、非法 URI 与 launcher failure；默认路径在触碰 AWT 前 fail-closed。
- GREEN：已迁移扩展列表、扩展详情、仓库设置、追踪 OAuth、漫画详情、更新器和网络登录共七条 browser launch 链；目录打开、分享/文件 reveal 复用同一隔离策略。
- GREEN：架构保护 `7/7`；相关平台和 UI 回归合计 `62/62`，0 failure、0 error、0 skipped。测试通过 MockK 明确验证未调用 `Desktop.isDesktopSupported/getDesktop/browse/open`，执行期间没有启动浏览器、Explorer 或 Finder。

### R5

- Python：Gradle coordinator `3/3`，agent handoff `3/3`，roadmap state guard `3/3`。
- Shell guard：`27/27`；真实 roadmap state guard：PASS。
- Desktop focused：12 个测试类、`62/62`，0 failure、0 error、0 skipped。
- 根 `spotlessCheck`：GREEN。
- 额外完整 Desktop JVM 套件实际执行 `2118` 项：`2114` 通过、`1` 失败、`3` 跳过。唯一失败为既有 `LibraryPageCompositionTest` 未提供 `LocalDesktopUiDependencies`；本方案未修改该测试、`LibraryTab` 或对应依赖提供链，因此记录为本方案范围外的全量门禁残留，不重复启动完整套件，也不把其产品修复扩入本方案。

结论：五类反复失败均已有可执行保护和精确行为证据。计划内验证全部 GREEN；额外全量套件的单一既有失败已如实隔离记录。
